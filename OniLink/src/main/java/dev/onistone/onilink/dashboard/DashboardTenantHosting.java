package dev.onistone.onilink.dashboard;

import dev.onistone.onilink.allowlist.ProxyAllowlist;
import dev.onistone.onilink.config.ProxyConfig;
import dev.onistone.onilink.listener.BedrockProxyListener;
import dev.onistone.onilink.permissions.ProxyPermissions;
import dev.onistone.onilink.control.ControlJson;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Single-container tenant catalog and isolated proxy-runtime supervisor.
 *
 * <p>Every tenant shares the provider's authenticated web control plane, but each proxy has its
 * own Bedrock listener, configuration directory, allowlist, forwarding key, backend set, and live
 * connection registry. No Pterodactyl API or additional Pterodactyl server is involved.</p>
 */
final class DashboardTenantHosting implements AutoCloseable {
    private static final int STORAGE_VERSION = 1;
    private static final String DEFAULT_BDS_PROFILE =
            "bds-1.26.44.3-linux-x86_64-06effdd00067f1ae";
    private static final Pattern SLUG = Pattern.compile("[a-z][a-z0-9-]{1,31}");
    private static final Pattern PUBLIC_HOST = Pattern.compile("[A-Za-z0-9._:-]{1,253}");
    private static final Set<PosixFilePermission> OWNER_ONLY = EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE);

    @FunctionalInterface
    interface RuntimeFactory {
        RuntimeHandle start(Path configPath) throws Exception;
    }

    interface RuntimeHandle extends AutoCloseable {
        DashboardControl control();

        @Override
        void close();
    }

    private final Path catalogPath;
    private final Path runtimeDirectory;
    private final Path handoffDirectory;
    private final DashboardAccounts accounts;
    private final RuntimeFactory runtimeFactory;
    private final int providerPort;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Tenant> tenants = new LinkedHashMap<>();
    private final Map<String, ProxyInstance> proxies = new LinkedHashMap<>();
    private final Map<String, RuntimeHandle> runtimes = new LinkedHashMap<>();
    private final Map<String, Set<String>> controlGrants = new LinkedHashMap<>();
    private final Map<String, TenantControlPreview> tenantControlPreviews = new LinkedHashMap<>();
    private boolean closed;

    DashboardTenantHosting(
            Path dataDirectory,
            DashboardAccounts accounts,
            RuntimeFactory runtimeFactory,
            int providerPort
    ) throws IOException {
        Path directory = dataDirectory.resolve("tenancy");
        this.catalogPath = directory.resolve("catalog.properties");
        this.runtimeDirectory = directory.resolve("runtimes");
        this.handoffDirectory = directory.resolve("handoffs");
        this.accounts = accounts;
        this.runtimeFactory = runtimeFactory;
        this.providerPort = providerPort;
        Files.createDirectories(runtimeDirectory);
        Files.createDirectories(handoffDirectory);
        loadCatalog();
        startConfiguredRuntimes();
    }

    static RuntimeFactory productionRuntimeFactory() {
        return configPath -> {
            ProxyConfig config = ProxyConfig.loadOrCreate(configPath);
            Path permissionsPath = configPath.toAbsolutePath().resolveSibling("permissions.properties");
            BedrockProxyListener listener = new BedrockProxyListener(
                    config,
                    ProxyPermissions.load(config.permissions(), permissionsPath),
                    ProxyAllowlist.load(config.allowlist()),
                    null,
                    configPath.toAbsolutePath().getParent().getParent().getFileName().toString(),
                    configPath.toAbsolutePath().getParent().getFileName().toString());
            try {
                listener.start(false);
            } catch (Exception exception) {
                listener.stop();
                throw exception;
            }
            ProxyDashboardControl control = new ProxyDashboardControl(config, listener);
            return new RuntimeHandle() {
                private boolean stopped;

                @Override
                public DashboardControl control() {
                    return control;
                }

                @Override
                public synchronized void close() {
                    if (stopped) return;
                    stopped = true;
                    control.close();
                    listener.stop();
                }
            };
        };
    }

    synchronized Map<String, Object> overview(String tenantScope) {
        String scope = normalizedScope(tenantScope);
        List<Map<String, Object>> visibleTenants = tenants.values().stream()
                .filter(tenant -> scope.isBlank() || tenant.id().equals(scope))
                .sorted(Comparator.comparing(Tenant::createdAt))
                .map(tenant -> tenant.asMap(accounts.tenantUsers(tenant.id())))
                .toList();
        List<Map<String, Object>> visibleProxies = proxies.values().stream()
                .filter(proxy -> scope.isBlank() || proxy.tenantId().equals(scope))
                .sorted(Comparator.comparing(ProxyInstance::tenantId).thenComparing(ProxyInstance::id))
                .map(this::proxyView)
                .toList();
        return Map.of(
                "mode", "single-container",
                "providerPort", providerPort,
                "tenants", visibleTenants,
                "proxies", visibleProxies,
                "tenantScope", scope);
    }

    synchronized Map<String, Object> createTenant(Map<String, String> form) throws IOException {
        ensureOpen();
        String tenantId = slug(form.get("tenant"), "tenant ID");
        if (tenants.containsKey(tenantId)) throw new IllegalStateException("Tenant already exists");
        String label = required(form.get("label"), "Tenant label", 100);
        String username = required(form.get("username"), "Tenant username", 32);
        String password = required(form.get("password"), "Tenant password", 256);
        if (accounts.hasUser(username)) throw new IllegalStateException("Dashboard username already exists");

        Tenant tenant = new Tenant(tenantId, label, false, Instant.now().toString(), Instant.now().toString());
        tenants.put(tenantId, tenant);
        boolean accountCreated = false;
        try {
            accounts.createTenantUser(username, tenantId, password);
            accountCreated = true;
            saveCatalog();
        } catch (IOException | RuntimeException exception) {
            tenants.remove(tenantId);
            if (accountCreated) {
                try {
                    accounts.deleteUser("", username);
                } catch (IOException | RuntimeException rollbackFailure) {
                    exception.addSuppressed(rollbackFailure);
                }
            }
            throw exception;
        }
        return Map.of("tenant", tenant.asMap(accounts.tenantUsers(tenantId)),
                "message", "Tenant and scoped dashboard account created.");
    }

    synchronized Map<String, Object> addTenantUser(Map<String, String> form) throws IOException {
        ensureOpen();
        String tenantId = existingTenant(form.get("tenant")).id();
        String username = required(form.get("username"), "Tenant username", 32);
        accounts.createTenantUser(username, tenantId,
                required(form.get("password"), "Tenant password", 256));
        return Map.of("users", accounts.tenantUsers(tenantId).stream()
                .map(DashboardAccounts.UserView::asMap).toList());
    }

    synchronized Map<String, Object> createProxy(Map<String, String> form) throws IOException {
        ensureOpen();
        Tenant tenant = existingTenant(form.get("tenant"));
        if (tenant.suspended()) throw new IllegalStateException("Restore the tenant before adding a proxy");
        String proxyId = slug(form.get("proxy"), "proxy ID");
        String key = runtimeKey(tenant.id(), proxyId);
        if (proxies.containsKey(key)) throw new IllegalStateException("Proxy already exists for this tenant");
        int port = boundedInt(form.get("port"), "listener port", 1, 65_535);
        if (port == providerPort || proxies.values().stream().anyMatch(proxy -> proxy.port() == port)) {
            throw new IllegalStateException("That allocation port is already used by this OniLink container");
        }
        Endpoint backend = endpoint(required(form.get("backendAddress"), "Backend address", 300));
        String publicHost = publicHost(form.get("publicHost"));
        String proxySourceCidr = exactCidr(form.get("proxySourceIp"));
        String profile = safeIdentifier(defaulted(form.get("bdsProfile"), DEFAULT_BDS_PROFILE),
                "BDS profile", 160);
        ProxyInstance proxy = new ProxyInstance(
                proxyId,
                tenant.id(),
                required(form.get("label"), "Proxy label", 100),
                port,
                displayEndpoint(publicHost, port),
                backend.display(),
                "default",
                proxySourceCidr,
                profile,
                boundedIntDefault(form.get("maxPlayers"), "maximum players", 20, 1, 10_000),
                required(defaulted(form.get("motd"), tenant.label() + " Network"), "MOTD", 200),
                true,
                "starting",
                "",
                Instant.now().toString(),
                Instant.now().toString());
        writeRuntimeFiles(proxy);
        writeHandoff(proxy);
        proxies.put(key, proxy);
        saveCatalog();
        ProxyInstance updated = startProxy(proxy);
        saveCatalog();
        return Map.of("proxy", proxyView(updated),
                "message", updated.status().equals("running")
                        ? "Tenant proxy started inside this OniLink container."
                        : "Tenant proxy was saved but could not start: " + updated.lastError());
    }

    synchronized Map<String, Object> tenantAction(String rawTenant, String rawAction) throws IOException {
        ensureOpen();
        Tenant tenant = existingTenant(rawTenant);
        String action = value(rawAction).trim().toLowerCase(Locale.ROOT);
        if (!List.of("suspend", "restore").contains(action)) {
            throw new IllegalArgumentException("Tenant action must be suspend or restore");
        }
        boolean suspended = "suspend".equals(action);
        Tenant updated = tenant.withSuspended(suspended);
        tenants.put(updated.id(), updated);
        for (ProxyInstance proxy : new ArrayList<>(proxies.values())) {
            if (!proxy.tenantId().equals(updated.id())) continue;
            if (suspended) stopProxy(proxy, "suspended", "");
            else if (proxy.enabled()) startProxy(proxy);
        }
        saveCatalog();
        return Map.of("tenant", updated.asMap(accounts.tenantUsers(updated.id())));
    }

    synchronized Map<String, Object> proxyAction(
            String rawTenant,
            String rawProxy,
            String rawAction
    ) throws IOException {
        ensureOpen();
        ProxyInstance proxy = existingProxy(rawTenant, rawProxy);
        String action = value(rawAction).trim().toLowerCase(Locale.ROOT);
        ProxyInstance updated;
        switch (action) {
            case "start" -> {
                if (existingTenant(proxy.tenantId()).suspended()) {
                    throw new IllegalStateException("Restore the tenant before starting its proxy");
                }
                updated = startProxy(proxy.withEnabled(true));
            }
            case "restart" -> {
                stopRuntime(proxy);
                updated = startProxy(proxy.withEnabled(true));
            }
            case "stop" -> updated = stopProxy(proxy.withEnabled(false), "stopped", "");
            default -> throw new IllegalArgumentException("Proxy action must be start, restart, or stop");
        }
        saveCatalog();
        String message = updated.status().equals("error")
                ? "Proxy could not start: " + updated.lastError()
                : "Proxy " + action + " completed.";
        return Map.of("proxy", proxyView(updated), "message", message);
    }

    synchronized Map<String, Object> proxyDashboard(String rawTenant, String rawProxy) throws IOException {
        ProxyInstance proxy = existingProxy(rawTenant, rawProxy);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("proxy", proxyView(proxy));
        RuntimeHandle runtime = runtimes.get(runtimeKey(proxy));
        if (runtime == null) {
            result.put("state", Map.of());
            result.put("players", List.of());
            result.put("backends", List.of());
            result.put("allowlist", Map.of("enabled", false, "count", 0, "entries", List.of()));
        } else {
            result.put("state", runtime.control().state());
            result.put("players", runtime.control().players(true));
            result.put("backends", runtime.control().backends(true));
            result.put("allowlist", runtime.control().allowlist());
        }
        DashboardConfigFile config = new DashboardConfigFile(configPath(proxy));
        result.putAll(config.routing());
        result.put("configurationRevision", config.read().get("revision"));
        return result;
    }

    synchronized Map<String, Object> packetMonitor(
            String rawTenant,
            String rawProxy,
            Map<String, String> filters
    ) {
        ProxyInstance proxy = existingProxy(rawTenant, rawProxy);
        return runningControl(proxy).packetMonitor(filters);
    }

    synchronized Map<String, Object> runtimeAction(Map<String, String> form) {
        ProxyInstance proxy = existingProxy(form.get("tenant"), form.get("proxy"));
        DashboardControl control = runningControl(proxy);
        String action = value(form.get("action")).trim().toLowerCase(Locale.ROOT);
        DashboardControl.ActionResult result = switch (action) {
            case "transfer" -> control.transfer(form.get("player"), form.get("backend"));
            case "disconnect" -> control.disconnect(form.get("player"), form.get("reason"));
            case "alert" -> control.alert(form.get("message"));
            case "trace" -> control.trace(form.get("player"), boundedLongDefault(
                    form.get("milliseconds"), "trace duration", 10_000, 1_000, 60_000));
            default -> throw new IllegalArgumentException(
                    "Runtime action must be transfer, disconnect, alert, or trace");
        };
        return result.asMap();
    }

    synchronized Map<String, Object> allowlist(
            String rawTenant,
            String rawProxy,
            String method,
            Map<String, String> form
    ) {
        ProxyInstance proxy = existingProxy(rawTenant, rawProxy);
        DashboardControl control = runningControl(proxy);
        if ("GET".equals(method)) return control.allowlist();
        DashboardControl.ActionResult result = "DELETE".equals(method)
                ? control.allowlistRemove(form.get("xuid"))
                : control.allowlistAdd(form.get("xuid"), form.get("name"));
        if (!result.success()) throw new IllegalStateException(result.message());
        return result.asMap();
    }

    synchronized Map<String, Object> addBackend(Map<String, String> form) throws IOException {
        ProxyInstance proxy = existingProxy(form.get("tenant"), form.get("proxy"));
        DashboardConfigFile config = new DashboardConfigFile(configPath(proxy));
        Map<String, Object> result = new LinkedHashMap<>(config.addBackend(form.get("revision"), form));
        ProxyInstance updated = proxy;
        boolean wasRunning = runtimes.containsKey(runtimeKey(proxy));
        if (wasRunning) {
            stopRuntime(proxy);
            updated = startProxy(proxy);
        }
        result.put("proxy", proxyView(updated));
        result.put("message", updated.status().equals("error")
                ? "Backend saved, but the proxy could not restart: " + updated.lastError()
                : wasRunning
                        ? "Backend added and the proxy restarted."
                        : "Backend added and will load when the proxy starts.");
        return Map.copyOf(result);
    }

    synchronized Map<String, Object> setPrimaryBackend(Map<String, String> form) throws IOException {
        ProxyInstance proxy = existingProxy(form.get("tenant"), form.get("proxy"));
        DashboardConfigFile config = new DashboardConfigFile(configPath(proxy));
        Map<String, Object> result = new LinkedHashMap<>(config.setPrimaryBackend(
                form.get("revision"), form.get("backend")));
        boolean changed = Boolean.TRUE.equals(result.get("changed"));
        boolean wasRunning = runtimes.containsKey(runtimeKey(proxy));
        ProxyInstance updated = proxy;
        if (changed) {
            updated = proxy.withPrimaryBackend(
                    String.valueOf(result.get("primaryBackend")),
                    String.valueOf(result.get("primaryBackendAddress")));
            replaceProxy(updated);
            if (wasRunning) {
                stopRuntime(proxy);
                updated = startProxy(updated);
            }
            saveCatalog();
        }
        result.put("proxy", proxyView(updated));
        if (changed) {
            result.put("message", updated.status().equals("error")
                    ? "Primary server saved, but the proxy could not restart: " + updated.lastError()
                    : wasRunning
                            ? "Primary server changed to " + result.get("primaryBackend")
                                    + " and the proxy restarted. New connections will use it."
                            : "Primary server changed to " + result.get("primaryBackend")
                                    + ". New connections will use it when the proxy starts.");
        }
        return Map.copyOf(result);
    }

    synchronized byte[] handoff(String rawTenant, String rawProxy) throws IOException {
        ProxyInstance proxy = existingProxy(rawTenant, rawProxy);
        Path path = handoffPath(proxy);
        if (!Files.isRegularFile(path)) throw new IllegalStateException("Proxy handoff is unavailable");
        return Files.readAllBytes(path);
    }

    private void startConfiguredRuntimes() throws IOException {
        boolean changed = false;
        for (ProxyInstance proxy : new ArrayList<>(proxies.values())) {
            Tenant tenant = tenants.get(proxy.tenantId());
            if (proxy.enabled() && tenant != null && !tenant.suspended()) {
                ProxyInstance updated = startProxy(proxy);
                changed |= updated != proxy;
            }
        }
        if (changed) saveCatalog();
    }

    private ProxyInstance startProxy(ProxyInstance proxy) {
        String key = runtimeKey(proxy);
        RuntimeHandle current = runtimes.get(key);
        if (current != null) return replaceProxy(proxy.withState("running", ""));
        try {
            RuntimeHandle runtime = runtimeFactory.start(configPath(proxy));
            runtimes.put(key, runtime);
            return replaceProxy(proxy.withState("running", ""));
        } catch (Exception exception) {
            return replaceProxy(proxy.withState("error", safeError(exception)));
        }
    }

    private ProxyInstance stopProxy(ProxyInstance proxy, String status, String error) {
        stopRuntime(proxy);
        return replaceProxy(proxy.withState(status, error));
    }

    private void stopRuntime(ProxyInstance proxy) {
        RuntimeHandle runtime = runtimes.remove(runtimeKey(proxy));
        if (runtime != null) runtime.close();
    }

    private ProxyInstance replaceProxy(ProxyInstance proxy) {
        proxies.put(runtimeKey(proxy), proxy);
        return proxy;
    }

    private DashboardControl runningControl(ProxyInstance proxy) {
        RuntimeHandle runtime = runtimes.get(runtimeKey(proxy));
        if (runtime == null) throw new IllegalStateException("Start the proxy before using runtime controls");
        return runtime.control();
    }

    synchronized DashboardControl runtimeControl(String tenantId, String proxyId) {
        ProxyInstance proxy = existingProxy(tenantId, proxyId);
        DashboardControl delegate = runningControl(proxy);
        Set<String> grants = Set.copyOf(controlGrants.getOrDefault(proxy.tenantId(), Set.of()));
        return new DashboardControl() {
            @Override public Map<String, Object> state() { return delegate.state(); }
            @Override public List<Map<String, Object>> players(boolean addresses) { return delegate.players(addresses); }
            @Override public List<Map<String, Object>> backends(boolean addresses) { return delegate.backends(addresses); }
            @Override public Map<String, Object> oniControlStatus() { return delegate.oniControlStatus(); }
            @Override public Map<String, Object> oniControlCapabilities(Map<String, String> values) {
                Map<String, Object> source = delegate.oniControlCapabilities(values);
                if (!"tenant".equalsIgnoreCase(values.getOrDefault("requestRole", "tenant"))) return source;
                Object actions = source.get("actions");
                if (!(actions instanceof List<?> list)) return source;
                List<Map<String, Object>> filtered = new ArrayList<>();
                for (Object item : list) {
                    if (!(item instanceof Map<?, ?> map)) continue;
                    Map<String, Object> copy = new LinkedHashMap<>();
                    map.forEach((key, value) -> copy.put(String.valueOf(key), value));
                    String action = String.valueOf(copy.get("action"));
                    if (!grants.contains(action)) {
                        copy.put("supported", false);
                        copy.put("reason", "The provider owner has not granted this action to the tenant");
                    }
                    filtered.add(Map.copyOf(copy));
                }
                return Map.of("target", source.getOrDefault("target", Map.of()), "actions", List.copyOf(filtered));
            }
            @Override public Map<String, Object> oniControlPreview(
                    String actor, String role, Map<String, String> values) {
                requireTenantGrant(role, grants, values.get("action"));
                Map<String, Object> preview = delegate.oniControlPreview(actor, role, values);
                if ("tenant".equalsIgnoreCase(role)) {
                    rememberTenantPreview(proxy, actor, preview,
                            Set.of(values.get("action").trim().toUpperCase(Locale.ROOT)));
                }
                return preview;
            }
            @Override public Map<String, Object> oniControlExecute(
                    String actor, String role, String token, boolean confirmed) {
                if ("tenant".equalsIgnoreCase(role)) consumeTenantPreview(proxy, actor, token);
                return delegate.oniControlExecute(actor, role, token, confirmed);
            }
            @Override public Map<String, Object> oniControlPlanValidate(
                    String actor, String role, Map<String, String> values) {
                requireTenantPlanGrants(role, grants, values.get("plan"));
                return delegate.oniControlPlanValidate(actor, role, values);
            }
            @Override public Map<String, Object> oniControlPlanPreview(
                    String actor, String role, Map<String, String> values) {
                Set<String> actions = requireTenantPlanGrants(role, grants, values.get("plan"));
                Map<String, Object> preview = delegate.oniControlPlanPreview(actor, role, values);
                if ("tenant".equalsIgnoreCase(role)) {
                    rememberTenantPreview(proxy, actor, preview, actions);
                }
                return preview;
            }
            @Override public Map<String, Object> oniControlPlanExecute(
                    String actor, String role, String token, boolean confirmed) {
                if ("tenant".equalsIgnoreCase(role)) consumeTenantPreview(proxy, actor, token);
                return delegate.oniControlPlanExecute(actor, role, token, confirmed);
            }
            @Override public Map<String, Object> oniControlHistory() { return delegate.oniControlHistory(); }
            @Override public Map<String, Object> oniPacketRules() { return delegate.oniPacketRules(); }
            @Override public ActionResult replaceOniPacketRules(String json) {
                return delegate.replaceOniPacketRules(json);
            }
            @Override public ActionResult transfer(String player, String backend) {
                return delegate.transfer(player, backend);
            }
            @Override public ActionResult disconnect(String player, String reason) {
                return delegate.disconnect(player, reason);
            }
            @Override public ActionResult alert(String message) { return delegate.alert(message); }
            @Override public ActionResult trace(String player, long milliseconds) {
                return delegate.trace(player, milliseconds);
            }
            @Override public void shutdown() { throw new IllegalStateException("Tenant cannot stop the provider runtime"); }
        };
    }

    synchronized String defaultProxy(String rawTenant) {
        String tenant = existingTenant(rawTenant).id();
        List<ProxyInstance> matches = proxies.values().stream()
                .filter(proxy -> proxy.tenantId().equals(tenant) && proxy.enabled()).toList();
        if (matches.size() != 1) throw new IllegalArgumentException(
                matches.isEmpty() ? "Tenant has no enabled proxy" : "Select a proxy for this tenant action");
        return matches.getFirst().id();
    }

    synchronized Map<String, Object> controlGrants(String rawTenant) {
        Tenant tenant = existingTenant(rawTenant);
        return Map.of("tenant", tenant.id(), "actions",
                controlGrants.getOrDefault(tenant.id(), Set.of()).stream().sorted().toList());
    }

    synchronized Map<String, Object> setControlGrants(String rawTenant, String json) throws IOException {
        Tenant tenant = existingTenant(rawTenant);
        Object raw = ControlJson.parseObject(json == null || json.isBlank() ? "{}" : json, 64 * 1024)
                .get("actions");
        if (!(raw instanceof List<?> list) || list.size() > 128) {
            throw new IllegalArgumentException("actions must be an array with at most 128 entries");
        }
        Set<String> values = new java.util.TreeSet<>();
        for (Object item : list) {
            if (!(item instanceof String action)) throw new IllegalArgumentException("grant actions must be text");
            try {
                dev.onistone.onilink.control.ActionType parsed =
                        dev.onistone.onilink.control.ActionType.valueOf(action.toUpperCase(Locale.ROOT));
                if (!dev.onistone.onilink.control.ControlRole.OPERATOR.allows(parsed.minimumRole())) {
                    throw new IllegalArgumentException("Tenant grants cannot include administrator or owner actions");
                }
                values.add(parsed.name());
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Unknown or forbidden tenant action " + action);
            }
        }
        controlGrants.put(tenant.id(), Set.copyOf(values));
        tenantControlPreviews.values().removeIf(preview -> preview.tenantId().equals(tenant.id()));
        saveCatalog();
        return controlGrants(tenant.id());
    }

    private Map<String, Object> proxyView(ProxyInstance proxy) {
        Map<String, Object> result = proxy.asMap();
        result.put("running", runtimes.containsKey(runtimeKey(proxy)));
        result.put("handoffAvailable", Files.isRegularFile(handoffPath(proxy)));
        return result;
    }

    private void writeRuntimeFiles(ProxyInstance proxy) throws IOException {
        Path directory = proxyDirectory(proxy);
        Files.createDirectories(directory.resolve("secrets"));
        Files.createDirectories(directory.resolve("cache"));
        Files.createDirectories(directory.resolve("resource-packs"));
        String secret = randomBase64(32);
        atomicWrite(secretPath(proxy), (secret + "\n").getBytes(StandardCharsets.UTF_8));

        Endpoint backend = endpoint(proxy.backendAddress());
        Properties properties = ProxyConfig.defaultProperties();
        properties.setProperty("listener.host", "0.0.0.0");
        properties.setProperty("listener.port", Integer.toString(proxy.port()));
        properties.setProperty("backend.name", "default");
        properties.setProperty("backend.host", backend.host());
        properties.setProperty("backend.port", Integer.toString(backend.port()));
        properties.setProperty("backends", "default");
        properties.setProperty("hubBackend", "default");
        properties.setProperty("backend.default.host", backend.host());
        properties.setProperty("backend.default.port", Integer.toString(backend.port()));
        properties.setProperty("forwarding.proxyId", "tenant-" + proxy.tenantId() + "-" + proxy.id());
        properties.setProperty("backend.default.forwarding.enabled", "true");
        properties.setProperty("backend.default.forwarding.bridgeId", proxy.id() + "-main");
        properties.setProperty("backend.default.forwarding.activeKeyId", "key-1");
        properties.setProperty("backend.default.forwarding.activeSecretEnv", "");
        properties.setProperty("backend.default.forwarding.activeSecretFile", "secrets/default.key");
        properties.setProperty("motd", proxy.motd());
        properties.setProperty("maxPlayers", Integer.toString(proxy.maxPlayers()));
        properties.setProperty("publicAddress", proxy.publicAddress());
        properties.setProperty("allowlist.file", "allowlist.properties");
        properties.setProperty("resourcePacks.dir", "resource-packs");
        properties.setProperty("dashboard.enabled", "false");
        properties.setProperty("dashboard.dataDirectory", "dashboard-disabled");
        storeProperties(configPath(proxy), properties,
                "OniLink single-container tenant proxy " + proxy.tenantId() + "/" + proxy.id());
    }

    private void writeHandoff(ProxyInstance proxy) throws IOException {
        String secret = Files.readString(secretPath(proxy), StandardCharsets.UTF_8).trim();
        String instructions = """
                ONILINK SHARED-CONTROL-PLANE TENANT HANDOFF

                Tenant: %s
                Proxy: %s (%s)
                Player address: %s
                Backend address: %s

                This proxy runs inside the provider's existing OniLink container. The customer signs in at the
                provider's one dashboard URL with the tenant account supplied separately. No Pterodactyl server,
                egg, or customer-side OniLink dashboard is created.

                BACKEND INSTALL
                1. Install the matching OniBridge .so on this proxy's BDS server.
                2. Upload backend/default.key and backend/onibridge.toml from this ZIP to:
                   /home/container/plugins/onibridge/
                3. Start BDS and confirm the production identity hook is active.
                4. Start or restart this proxy from the provider control plane.

                Treat this ZIP as a secret. It contains the backend forwarding key.
                """.formatted(proxy.tenantId(), proxy.id(), proxy.label(), proxy.publicAddress(),
                proxy.backendAddress());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            zip(zip, "CUSTOMER-START-HERE.txt", instructions);
            zip(zip, "backend/default.key", secret + "\n");
            zip(zip, "backend/onibridge.toml", onibridgeToml(
                    proxy.id(), proxy.bdsProfile(), proxy.trustedProxyCidr()));
        }
        atomicWrite(handoffPath(proxy), bytes.toByteArray());
    }

    private static void zip(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String onibridgeToml(String proxyId, String profile, String trustedProxyCidr) {
        return """
                # Generated for one proxy in the OniLink shared control plane.
                # Install as: /home/container/plugins/onibridge/onibridge.toml

                bridge_id = "%s-main"
                backend_name = "default"
                trusted_proxy_cidrs = ["%s"]
                shutdown_on_hook_failure = true
                reject_direct_joins = true

                [forwarding]
                protocol = 2
                active_key_id = "key-1"
                active_secret_env = ""
                active_secret_file = "default.key"
                previous_key_id = ""
                previous_secret_env = ""
                previous_secret_file = ""
                maximum_token_size = 4096
                maximum_lifetime_ms = 10000
                allowed_clock_skew_ms = 2000
                replay_cache_max_entries = 10000

                [identity]
                uuid_mode = "preserve_backend"
                verify_post_login_xuid = true
                store_verified_identities = true

                [commands]
                register_native_commands = true
                command_namespace = "onibridge"
                interfere_with_backend_commands = false

                [compatibility]
                required_profile = "%s"
                allow_unreviewed_profile = false
                allow_unknown_bds = false
                allow_unknown_endstone = false

                [legacy_verification]
                enabled = false
                """.formatted(proxyId, trustedProxyCidr, profile);
    }

    private void loadCatalog() throws IOException {
        if (!Files.isRegularFile(catalogPath)) return;
        Properties properties = loadProperties(catalogPath);
        int version = integer(properties.getProperty("version", "0"), "tenancy storage version");
        if (version != STORAGE_VERSION) throw new IOException("Unsupported tenancy storage version: " + version);
        for (String id : csv(properties.getProperty("tenants", ""))) {
            String prefix = "tenant." + id + ".";
            Tenant tenant = new Tenant(
                    slug(id, "stored tenant ID"),
                    properties.getProperty(prefix + "label", id),
                    Boolean.parseBoolean(properties.getProperty(prefix + "suspended", "false")),
                    properties.getProperty(prefix + "createdAt", ""),
                    properties.getProperty(prefix + "updatedAt", ""));
            tenants.put(tenant.id(), tenant);
            controlGrants.put(tenant.id(), Set.copyOf(csv(
                    properties.getProperty(prefix + "controlActions", ""))));
        }
        for (String item : csv(properties.getProperty("proxies", ""))) {
            String[] ids = item.split("/", 2);
            if (ids.length != 2) throw new IOException("Invalid stored tenant proxy ID: " + item);
            String tenantId = slug(ids[0], "stored tenant ID");
            String proxyId = slug(ids[1], "stored proxy ID");
            if (!tenants.containsKey(tenantId)) throw new IOException("Stored proxy references an unknown tenant");
            String prefix = "proxy." + tenantId + "." + proxyId + ".";
            ProxyInstance proxy = new ProxyInstance(
                    proxyId,
                    tenantId,
                    properties.getProperty(prefix + "label", proxyId),
                    integer(properties.getProperty(prefix + "port"), "stored proxy port"),
                    properties.getProperty(prefix + "publicAddress", ""),
                    properties.getProperty(prefix + "backendAddress", ""),
                    properties.getProperty(prefix + "primaryBackend", "default"),
                    properties.getProperty(prefix + "trustedProxyCidr", ""),
                    properties.getProperty(prefix + "bdsProfile", DEFAULT_BDS_PROFILE),
                    integer(properties.getProperty(prefix + "maxPlayers", "20"), "stored maximum players"),
                    properties.getProperty(prefix + "motd", "OniLink"),
                    Boolean.parseBoolean(properties.getProperty(prefix + "enabled", "true")),
                    properties.getProperty(prefix + "status", "stopped"),
                    properties.getProperty(prefix + "lastError", ""),
                    properties.getProperty(prefix + "createdAt", ""),
                    properties.getProperty(prefix + "updatedAt", ""));
            if (proxy.port() == providerPort || proxies.values().stream().anyMatch(value -> value.port() == proxy.port())) {
                throw new IOException("Stored proxy allocation conflicts on port " + proxy.port());
            }
            proxies.put(runtimeKey(proxy), proxy);
        }
    }

    private void saveCatalog() throws IOException {
        Properties properties = new Properties();
        properties.setProperty("version", Integer.toString(STORAGE_VERSION));
        properties.setProperty("tenants", String.join(",", tenants.keySet()));
        for (Tenant tenant : tenants.values()) {
            String prefix = "tenant." + tenant.id() + ".";
            properties.setProperty(prefix + "label", tenant.label());
            properties.setProperty(prefix + "suspended", Boolean.toString(tenant.suspended()));
            properties.setProperty(prefix + "createdAt", tenant.createdAt());
            properties.setProperty(prefix + "updatedAt", tenant.updatedAt());
            properties.setProperty(prefix + "controlActions", String.join(",",
                    controlGrants.getOrDefault(tenant.id(), Set.of()).stream().sorted().toList()));
        }
        properties.setProperty("proxies", String.join(",", proxies.values().stream()
                .map(proxy -> proxy.tenantId() + "/" + proxy.id()).toList()));
        for (ProxyInstance proxy : proxies.values()) {
            String prefix = "proxy." + proxy.tenantId() + "." + proxy.id() + ".";
            properties.setProperty(prefix + "label", proxy.label());
            properties.setProperty(prefix + "port", Integer.toString(proxy.port()));
            properties.setProperty(prefix + "publicAddress", proxy.publicAddress());
            properties.setProperty(prefix + "backendAddress", proxy.backendAddress());
            properties.setProperty(prefix + "primaryBackend", proxy.primaryBackend());
            properties.setProperty(prefix + "trustedProxyCidr", proxy.trustedProxyCidr());
            properties.setProperty(prefix + "bdsProfile", proxy.bdsProfile());
            properties.setProperty(prefix + "maxPlayers", Integer.toString(proxy.maxPlayers()));
            properties.setProperty(prefix + "motd", proxy.motd());
            properties.setProperty(prefix + "enabled", Boolean.toString(proxy.enabled()));
            properties.setProperty(prefix + "status", proxy.status());
            properties.setProperty(prefix + "lastError", proxy.lastError());
            properties.setProperty(prefix + "createdAt", proxy.createdAt());
            properties.setProperty(prefix + "updatedAt", proxy.updatedAt());
        }
        storeProperties(catalogPath, properties,
                "OniLink single-container tenant and proxy catalog — contains no forwarding keys");
    }

    private Tenant existingTenant(String rawTenant) {
        String tenantId = slug(rawTenant, "tenant ID");
        return Optional.ofNullable(tenants.get(tenantId))
                .orElseThrow(() -> new IllegalArgumentException("Tenant does not exist"));
    }

    private ProxyInstance existingProxy(String rawTenant, String rawProxy) {
        String tenantId = existingTenant(rawTenant).id();
        String proxyId = slug(rawProxy, "proxy ID");
        return Optional.ofNullable(proxies.get(runtimeKey(tenantId, proxyId)))
                .orElseThrow(() -> new IllegalArgumentException("Proxy does not exist for this tenant"));
    }

    private Path proxyDirectory(ProxyInstance proxy) {
        return runtimeDirectory.resolve(proxy.tenantId()).resolve(proxy.id());
    }

    private Path configPath(ProxyInstance proxy) {
        return proxyDirectory(proxy).resolve("config.properties");
    }

    private Path secretPath(ProxyInstance proxy) {
        return proxyDirectory(proxy).resolve("secrets").resolve("default.key");
    }

    private Path handoffPath(ProxyInstance proxy) {
        return handoffDirectory.resolve(proxy.tenantId() + "--" + proxy.id() + ".handoff.zip");
    }

    private String randomBase64(int bytes) {
        byte[] data = new byte[bytes];
        random.nextBytes(data);
        return Base64.getEncoder().encodeToString(data);
    }

    private static void storeProperties(Path target, Properties properties, String comment) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        properties.store(bytes, comment);
        atomicWrite(target, bytes.toByteArray());
    }

    private static Properties loadProperties(Path path) throws IOException {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            properties.load(input);
        }
        return properties;
    }

    private static void atomicWrite(Path target, byte[] content) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try (OutputStream output = Files.newOutputStream(temporary)) {
            output.write(content);
        }
        ownerOnly(temporary);
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
        ownerOnly(target);
    }

    private static void ownerOnly(Path path) {
        try {
            Files.setPosixFilePermissions(path, OWNER_ONLY);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows inherits ACLs; POSIX deployments get explicit owner-only credential files.
        }
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("Tenant control plane is closed");
    }

    private static String normalizedScope(String raw) {
        if (raw == null || raw.isBlank()) return "";
        return slug(raw, "tenant scope");
    }

    private static String runtimeKey(ProxyInstance proxy) {
        return runtimeKey(proxy.tenantId(), proxy.id());
    }

    private static String runtimeKey(String tenantId, String proxyId) {
        return tenantId + "/" + proxyId;
    }

    private static String slug(String raw, String label) {
        String candidate = required(raw, label, 32).toLowerCase(Locale.ROOT);
        if (!SLUG.matcher(candidate).matches()) {
            throw new IllegalArgumentException(
                    label + " must be 2-32 lowercase letters, numbers, or hyphens and start with a letter");
        }
        return candidate;
    }

    private static String required(String raw, String label, int maximum) {
        String candidate = value(raw).trim();
        if (candidate.isBlank()) throw new IllegalArgumentException(label + " is required");
        if (candidate.length() > maximum) throw new IllegalArgumentException(label + " is too long");
        return candidate;
    }

    private static String defaulted(String raw, String fallback) {
        String candidate = value(raw).trim();
        return candidate.isBlank() ? fallback : candidate;
    }

    private static String safeIdentifier(String raw, String label, int maximum) {
        String candidate = required(raw, label, maximum);
        if (!candidate.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException(
                    label + " may contain only letters, numbers, periods, underscores, and hyphens");
        }
        return candidate;
    }

    private static String publicHost(String raw) {
        String candidate = required(raw, "Public proxy host", 253);
        if (candidate.startsWith("[") && candidate.endsWith("]")) {
            candidate = candidate.substring(1, candidate.length() - 1);
        }
        if (!PUBLIC_HOST.matcher(candidate).matches() || candidate.startsWith(".") || candidate.endsWith(".")) {
            throw new IllegalArgumentException("Public proxy host must be an IP address or DNS name without a port");
        }
        return candidate;
    }

    private static int boundedInt(String raw, String label, int minimum, int maximum) {
        return boundedIntDefault(raw, label, Integer.MIN_VALUE, minimum, maximum);
    }

    private static int boundedIntDefault(String raw, String label, int fallback, int minimum, int maximum) {
        String candidate = value(raw).trim();
        if (candidate.isBlank() && fallback != Integer.MIN_VALUE) return fallback;
        int parsed = integer(candidate, label);
        if (parsed < minimum || parsed > maximum) {
            throw new IllegalArgumentException(label + " must be between " + minimum + " and " + maximum);
        }
        return parsed;
    }

    private static long boundedLongDefault(String raw, String label, long fallback, long minimum, long maximum) {
        String candidate = value(raw).trim();
        long parsed;
        try {
            parsed = candidate.isBlank() ? fallback : Long.parseLong(candidate);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(label + " must be a number");
        }
        if (parsed < minimum || parsed > maximum) {
            throw new IllegalArgumentException(label + " must be between " + minimum + " and " + maximum);
        }
        return parsed;
    }

    private static int integer(String raw, String label) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(label + " must be a number");
        }
    }

    private static Endpoint endpoint(String raw) {
        String candidate = raw.trim();
        String host;
        String portText;
        if (candidate.startsWith("[")) {
            int close = candidate.indexOf(']');
            if (close < 2 || close + 2 > candidate.length() || candidate.charAt(close + 1) != ':') {
                throw new IllegalArgumentException("Backend address must be host:port or [IPv6]:port");
            }
            host = candidate.substring(1, close);
            portText = candidate.substring(close + 2);
        } else {
            int separator = candidate.lastIndexOf(':');
            if (separator < 1 || candidate.indexOf(':') != separator) {
                throw new IllegalArgumentException("Backend address must be host:port or [IPv6]:port");
            }
            host = candidate.substring(0, separator);
            portText = candidate.substring(separator + 1);
        }
        if (host.isBlank() || host.length() > 253) throw new IllegalArgumentException("Backend host is invalid");
        return new Endpoint(host, boundedInt(portText, "backend port", 1, 65_535));
    }

    private static String exactCidr(String raw) {
        String candidate = required(raw, "Proxy source IP", 128);
        if (!candidate.matches("[0-9a-fA-F:.]+")) {
            throw new IllegalArgumentException("Proxy source IP must be an IPv4 or IPv6 address, not a hostname");
        }
        try {
            InetAddress parsed = InetAddress.getByName(candidate);
            return parsed.getHostAddress() + (parsed.getAddress().length == 4 ? "/32" : "/128");
        } catch (IOException exception) {
            throw new IllegalArgumentException("Proxy source IP is invalid");
        }
    }

    private static String displayEndpoint(String host, int port) {
        return (host.contains(":") ? "[" + host + "]" : host) + ":" + port;
    }

    private static String safeError(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) message = exception.getClass().getSimpleName();
        return message.substring(0, Math.min(message.length(), 500));
    }

    private static List<String> csv(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        List<String> values = new ArrayList<>();
        for (String item : raw.split(",")) if (!item.isBlank()) values.add(item.trim());
        return values;
    }

    private static String value(String raw) {
        return raw == null ? "" : raw;
    }

    private static void requireTenantGrant(String role, Set<String> grants, String rawAction) {
        if (!"tenant".equalsIgnoreCase(role)) return;
        String action = rawAction == null ? "" : rawAction.trim().toUpperCase(Locale.ROOT);
        if (!grants.contains(action)) {
            throw new IllegalArgumentException("The provider owner has not granted this action to the tenant");
        }
    }

    private static Set<String> requireTenantPlanGrants(String role, Set<String> grants, String json) {
        if (!"tenant".equalsIgnoreCase(role)) return Set.of();
        Map<String, Object> document = ControlJson.parseObject(json == null ? "" : json, 64 * 1024);
        Object rawSteps = document.get("steps");
        if (!(rawSteps instanceof List<?> steps)) throw new IllegalArgumentException("plan steps are required");
        Set<String> actions = new java.util.TreeSet<>();
        for (Object raw : steps) {
            if (!(raw instanceof Map<?, ?> step) || !(step.get("action") instanceof String action)) {
                throw new IllegalArgumentException("every plan step requires a typed action");
            }
            requireTenantGrant(role, grants, action);
            actions.add(action.trim().toUpperCase(Locale.ROOT));
        }
        return Set.copyOf(actions);
    }

    private synchronized void rememberTenantPreview(
            ProxyInstance proxy,
            String actor,
            Map<String, Object> preview,
            Set<String> actions
    ) {
        String token = String.valueOf(preview.getOrDefault("confirmationToken", ""));
        if (token.isBlank()) throw new IllegalStateException("control preview did not return a confirmation token");
        Instant now = Instant.now();
        tenantControlPreviews.values().removeIf(value -> !value.expiresAt().isAfter(now));
        tenantControlPreviews.put(previewDigest(token), new TenantControlPreview(
                proxy.tenantId(), proxy.id(), actor, Set.copyOf(actions), now.plusSeconds(60)));
    }

    private synchronized void consumeTenantPreview(ProxyInstance proxy, String actor, String token) {
        if (token == null || token.isBlank()) throw new IllegalArgumentException("confirmation token is required");
        TenantControlPreview preview = tenantControlPreviews.remove(previewDigest(token));
        if (preview == null || !preview.expiresAt().isAfter(Instant.now())
                || !preview.tenantId().equals(proxy.tenantId()) || !preview.proxyId().equals(proxy.id())
                || !preview.actor().equals(actor)) {
            throw new IllegalArgumentException("tenant confirmation token is invalid or expired");
        }
        Set<String> current = controlGrants.getOrDefault(proxy.tenantId(), Set.of());
        if (!current.containsAll(preview.actions())) {
            throw new IllegalArgumentException("the provider owner revoked an action after preview");
        }
    }

    private static String previewDigest(String token) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        for (RuntimeHandle runtime : new ArrayList<>(runtimes.values())) runtime.close();
        runtimes.clear();
    }

    private record Tenant(String id, String label, boolean suspended, String createdAt, String updatedAt) {
        Map<String, Object> asMap(List<DashboardAccounts.UserView> users) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", id);
            result.put("label", label);
            result.put("suspended", suspended);
            result.put("users", users.stream().map(DashboardAccounts.UserView::asMap).toList());
            result.put("createdAt", createdAt);
            result.put("updatedAt", updatedAt);
            return result;
        }

        Tenant withSuspended(boolean value) {
            return new Tenant(id, label, value, createdAt, Instant.now().toString());
        }
    }

    private record TenantControlPreview(
            String tenantId,
            String proxyId,
            String actor,
            Set<String> actions,
            Instant expiresAt
    ) {
    }

    private record ProxyInstance(
            String id,
            String tenantId,
            String label,
            int port,
            String publicAddress,
            String backendAddress,
            String primaryBackend,
            String trustedProxyCidr,
            String bdsProfile,
            int maxPlayers,
            String motd,
            boolean enabled,
            String status,
            String lastError,
            String createdAt,
            String updatedAt
    ) {
        Map<String, Object> asMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", id);
            result.put("tenantId", tenantId);
            result.put("label", label);
            result.put("port", port);
            result.put("publicAddress", publicAddress);
            result.put("backendAddress", backendAddress);
            result.put("primaryBackend", primaryBackend);
            result.put("trustedProxyCidr", trustedProxyCidr);
            result.put("bdsProfile", bdsProfile);
            result.put("maxPlayers", maxPlayers);
            result.put("motd", motd);
            result.put("enabled", enabled);
            result.put("status", status);
            result.put("lastError", lastError);
            result.put("createdAt", createdAt);
            result.put("updatedAt", updatedAt);
            return result;
        }

        ProxyInstance withEnabled(boolean value) {
            return new ProxyInstance(id, tenantId, label, port, publicAddress, backendAddress,
                    primaryBackend, trustedProxyCidr, bdsProfile, maxPlayers, motd, value, status, lastError,
                    createdAt, Instant.now().toString());
        }

        ProxyInstance withState(String state, String error) {
            return new ProxyInstance(id, tenantId, label, port, publicAddress, backendAddress,
                    primaryBackend, trustedProxyCidr, bdsProfile, maxPlayers, motd, enabled, state, error,
                    createdAt, Instant.now().toString());
        }

        ProxyInstance withPrimaryBackend(String name, String address) {
            return new ProxyInstance(id, tenantId, label, port, publicAddress, address,
                    name, trustedProxyCidr, bdsProfile, maxPlayers, motd, enabled, status, lastError,
                    createdAt, Instant.now().toString());
        }
    }

    private record Endpoint(String host, int port) {
        String display() {
            return displayEndpoint(host, port);
        }
    }
}
