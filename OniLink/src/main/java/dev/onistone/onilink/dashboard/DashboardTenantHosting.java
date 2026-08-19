package dev.onistone.onilink.dashboard;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
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

/** Owner-only Pterodactyl tenant provisioning and lifecycle storage for the dashboard. */
final class DashboardTenantHosting {
    private static final int STORAGE_VERSION = 1;
    private static final Pattern SLUG = Pattern.compile("[a-z][a-z0-9-]{1,31}");
    private static final Set<PosixFilePermission> OWNER_ONLY = EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE);

    private final Path settingsPath;
    private final Path tenantsPath;
    private final Path handoffDirectory;
    private final SecureRandom random = new SecureRandom();
    private Settings settings;
    private final Map<String, HostingPlan> plans = new LinkedHashMap<>();
    private final Map<String, Tenant> tenants = new LinkedHashMap<>();

    DashboardTenantHosting(Path dataDirectory) throws IOException {
        Path directory = dataDirectory.resolve("hosting");
        this.settingsPath = directory.resolve("settings.properties");
        this.tenantsPath = directory.resolve("tenants.properties");
        this.handoffDirectory = directory.resolve("handoffs");
        Files.createDirectories(handoffDirectory);
        this.settings = loadSettings();
        loadPlans();
        loadTenants();
        if (plans.isEmpty()) {
            plans.put("starter", new HostingPlan("starter", "Starter", 1024, 0,
                    1024, 500, 100, "", 0, 0, 1, 20));
            saveSettings();
        }
    }

    synchronized Map<String, Object> overview() {
        return Map.of(
                "settings", settings.asMap(),
                "plans", plans.values().stream().map(HostingPlan::asMap).toList(),
                "tenants", tenants.values().stream()
                        .sorted(Comparator.comparing(Tenant::createdAt).reversed())
                        .map(Tenant::asMap).toList()
        );
    }

    synchronized Map<String, Object> saveSettings(Map<String, String> form) throws IOException {
        String panelUrl = normalizedPanelUrl(form.get("panelUrl"));
        String submittedKey = value(form.get("apiKey")).trim();
        String apiKey = submittedKey.isBlank() ? settings.apiKey() : submittedKey;
        if (apiKey.isBlank()) throw new IllegalArgumentException("Enter a Pterodactyl application API key");
        int eggId = boundedIntDefault(form.get("eggId"), "egg ID", 0, 0, Integer.MAX_VALUE);
        String dockerImage = required(form.get("dockerImage"), "Docker image", 512);
        String startup = required(form.get("startup"), "Startup command", 2_000);
        String onilinkVersion = safeIdentifier(form.get("onilinkVersion"), "OniLink version", 64);
        String bdsProfile = safeIdentifier(form.get("bdsProfile"), "BDS profile", 160);
        this.settings = new Settings(panelUrl, apiKey, eggId, dockerImage, startup,
                onilinkVersion, bdsProfile, Instant.now().toString());
        saveSettings();
        return settings.asMap();
    }

    synchronized Map<String, Object> testConnection() throws IOException {
        PterodactylApplicationClient client = client();
        List<Map<String, Object>> nodes = client.list("/nodes");
        return Map.of(
                "connected", true,
                "panelUrl", settings.panelUrl(),
                "nodes", nodes.size(),
                "message", "Connected to Pterodactyl and read " + nodes.size() + " node(s)."
        );
    }

    synchronized Map<String, Object> discovery() throws IOException {
        PterodactylApplicationClient client = client();
        List<Map<String, Object>> users = client.list("/users").stream().map(item -> Map.<String, Object>of(
                "id", number(item.get("id")),
                "username", text(item.get("username")),
                "email", text(item.get("email")),
                "name", displayName(item)
        )).toList();
        List<Map<String, Object>> nodes = client.list("/nodes").stream().map(item -> Map.<String, Object>of(
                "id", number(item.get("id")),
                "name", text(item.get("name")),
                "fqdn", text(item.get("fqdn"))
        )).toList();
        List<Map<String, Object>> eggs = new ArrayList<>();
        for (Map<String, Object> nest : client.list("/nests")) {
            int nestId = number(nest.get("id"));
            String nestName = text(nest.get("name"));
            for (Map<String, Object> egg : client.list("/nests/" + nestId + "/eggs")) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", number(egg.get("id")));
                item.put("name", text(egg.get("name")));
                item.put("nest", nestName);
                item.put("startup", text(egg.get("startup")));
                item.put("dockerImage", preferredDockerImage(egg));
                eggs.add(item);
            }
        }
        return Map.of("users", users, "nodes", nodes, "eggs", eggs);
    }

    synchronized Map<String, Object> allocations(int nodeId) throws IOException {
        if (nodeId < 1) throw new IllegalArgumentException("Select a Pterodactyl node");
        List<Map<String, Object>> available = client().list("/nodes/" + nodeId + "/allocations?filter[server_id]=")
                .stream()
                .filter(item -> !booleanValue(item.get("assigned")))
                .map(item -> Map.<String, Object>of(
                        "id", number(item.get("id")),
                        "ip", text(item.get("ip")),
                        "alias", text(item.get("alias")),
                        "port", number(item.get("port")),
                        "address", endpointAddress(item)
                )).toList();
        return Map.of("allocations", available);
    }

    synchronized Map<String, Object> savePlan(Map<String, String> form) throws IOException {
        HostingPlan plan = new HostingPlan(
                slug(form.get("id"), "plan ID"),
                required(form.get("name"), "Plan name", 80),
                boundedInt(form.get("memory"), "memory", 128, 1_048_576),
                boundedIntDefault(form.get("swap"), "swap", 0, -1, 1_048_576),
                boundedInt(form.get("disk"), "disk", 128, 10_485_760),
                boundedIntDefault(form.get("io"), "I/O weight", 500, 10, 1_000),
                boundedInt(form.get("cpu"), "CPU", 1, 100_000),
                optional(form.get("threads"), 256),
                boundedIntDefault(form.get("databases"), "databases", 0, 0, 100),
                0,
                boundedIntDefault(form.get("backups"), "backups", 1, 0, 100),
                boundedIntDefault(form.get("maxPlayers"), "maximum players", 20, 1, 10_000)
        );
        plans.put(plan.id(), plan);
        saveSettings();
        return Map.of("plan", plan.asMap(), "created", true);
    }

    synchronized void deletePlan(String rawPlanId) throws IOException {
        String planId = slug(rawPlanId, "plan ID");
        if (!plans.containsKey(planId)) throw new IllegalArgumentException("Hosting plan does not exist");
        if (tenants.values().stream().anyMatch(tenant -> tenant.planId().equals(planId))) {
            throw new IllegalStateException("This plan is assigned to an existing tenant");
        }
        if (plans.size() == 1) throw new IllegalStateException("At least one hosting plan is required");
        plans.remove(planId);
        saveSettings();
    }

    synchronized Map<String, Object> createCustomer(Map<String, String> form) throws IOException {
        String password = required(form.get("password"), "Temporary password", 256);
        if (password.length() < 12) throw new IllegalArgumentException("Temporary password must be at least 12 characters");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("email", required(form.get("email"), "Email", 254));
        payload.put("username", required(form.get("username"), "Username", 64));
        payload.put("first_name", required(form.get("firstName"), "First name", 64));
        payload.put("last_name", required(form.get("lastName"), "Last name", 64));
        payload.put("password", password);
        payload.put("root_admin", false);
        Map<String, Object> created = client().item("POST", "/users", payload);
        return Map.of("user", Map.of(
                "id", number(created.get("id")),
                "username", text(created.get("username")),
                "email", text(created.get("email")),
                "name", displayName(created)
        ));
    }

    synchronized Map<String, Object> provision(Map<String, String> form) throws IOException {
        requireConfigured();
        String tenantId = slug(form.get("tenant"), "tenant ID");
        if (tenants.containsKey(tenantId)) {
            throw new IllegalStateException("Tenant already exists in this control plane");
        }
        String planId = slug(form.get("plan"), "plan ID");
        HostingPlan plan = Optional.ofNullable(plans.get(planId))
                .orElseThrow(() -> new IllegalArgumentException("Select a valid hosting plan"));
        int userId = positiveInt(form.get("userId"), "customer");
        int nodeId = positiveInt(form.get("nodeId"), "node");
        int allocationId = positiveInt(form.get("allocationId"), "allocation");
        PterodactylApplicationClient client = client();
        Map<String, Object> allocation = client.list("/nodes/" + nodeId + "/allocations?filter[server_id]=")
                .stream()
                .filter(item -> number(item.get("id")) == allocationId)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("The selected allocation is not available"));
        if (booleanValue(allocation.get("assigned"))) {
            throw new IllegalStateException("The selected allocation is already assigned");
        }
        if (client.findTenantServer(tenantId).isPresent()) {
            throw new IllegalStateException(
                    "A Pterodactyl server already uses this tenant ID; choose another ID or recover its original record");
        }
        Endpoint backend = endpoint(required(form.get("backendAddress"), "Backend address", 300));
        String proxyAddress = endpointAddress(allocation);
        String sourceIp = optional(form.get("proxySourceIp"), 128);
        if (sourceIp.isBlank()) sourceIp = text(allocation.get("ip"));
        String trustedProxyCidr = exactCidr(sourceIp);
        String forwardingSecret = randomBase64(32);
        String setupCode = randomUrlToken(24);
        Tenant prepared = new Tenant(
                tenantId,
                required(form.get("customerLabel"), "Customer label", 100),
                planId,
                userId,
                optional(form.get("userDisplay"), 160),
                nodeId,
                allocationId,
                proxyAddress,
                backend.display(),
                trustedProxyCidr,
                forwardingSecret,
                setupCode,
                0,
                "",
                false,
                "provisioning",
                "",
                Instant.now().toString(),
                Instant.now().toString()
        );
        tenants.put(tenantId, prepared);
        writeHandoff(prepared);
        saveTenants();
        return completeProvision(prepared, plan, false);
    }

    synchronized Map<String, Object> action(String rawTenant, String rawAction) throws IOException {
        String tenantId = slug(rawTenant, "tenant ID");
        Tenant tenant = Optional.ofNullable(tenants.get(tenantId))
                .orElseThrow(() -> new IllegalArgumentException("Tenant does not exist"));
        String action = value(rawAction).trim().toLowerCase(Locale.ROOT);
        if ("retry".equals(action)) {
            HostingPlan plan = Optional.ofNullable(plans.get(tenant.planId()))
                    .orElseThrow(() -> new IllegalStateException("The tenant's hosting plan no longer exists"));
            return completeProvision(tenant.withState("provisioning", ""), plan, true);
        }
        PterodactylApplicationClient client = client();
        Map<String, Object> remote = client.findTenantServer(tenantId)
                .orElseThrow(() -> new IllegalStateException("The tenant server was not found in Pterodactyl"));
        int serverId = number(remote.get("id"));
        if ("suspend".equals(action) || "unsuspend".equals(action)) {
            client.call("POST", "/servers/" + serverId + "/" + action, Map.of());
            remote = client.findTenantServer(tenantId).orElse(remote);
        } else if (!"refresh".equals(action)) {
            throw new IllegalArgumentException("Unknown tenant action");
        }
        Tenant updated = tenant.withRemote(remote);
        tenants.put(tenantId, updated);
        saveTenants();
        return Map.of("tenant", updated.asMap());
    }

    synchronized byte[] handoff(String rawTenant) throws IOException {
        String tenantId = slug(rawTenant, "tenant ID");
        if (!tenants.containsKey(tenantId)) throw new IllegalArgumentException("Tenant does not exist");
        Path path = handoffPath(tenantId);
        if (!Files.isRegularFile(path)) throw new IllegalStateException("Tenant handoff is unavailable");
        return Files.readAllBytes(path);
    }

    private Map<String, Object> completeProvision(
            Tenant tenant,
            HostingPlan plan,
            boolean reconcileExisting
    ) throws IOException {
        PterodactylApplicationClient client = client();
        try {
            Map<String, Object> remote = reconcileExisting ? client.findTenantServer(tenant.id()).orElse(null) : null;
            if (remote == null) remote = client.item("POST", "/servers", serverRequest(tenant, plan));
            Tenant updated = tenant.withRemote(remote);
            tenants.put(tenant.id(), updated);
            saveTenants();
            return Map.of("tenant", updated.asMap(), "message", "Tenant server provisioned successfully.");
        } catch (IOException | RuntimeException exception) {
            Tenant failed = tenant.withState("error", safeError(exception.getMessage(), tenant));
            tenants.put(tenant.id(), failed);
            saveTenants();
            throw exception;
        }
    }

    private Map<String, Object> serverRequest(Tenant tenant, HostingPlan plan) {
        Endpoint backend = endpoint(tenant.backendAddress());
        Map<String, Object> environment = new LinkedHashMap<>();
        environment.put("ONILINK_VERSION", settings.onilinkVersion());
        environment.put("SERVER_JARFILE", "OniLink.jar");
        environment.put("CONFIG_FILE", "config.properties");
        environment.put("DASHBOARD_ENABLED", "true");
        environment.put("ALLOWLIST_ENABLED", "false");
        environment.put("BACKEND_HOST", backend.host());
        environment.put("BACKEND_PORT", Integer.toString(backend.port()));
        environment.put("SERVER_MOTD", tenant.customerLabel() + " Network");
        environment.put("MAX_PLAYERS", Integer.toString(plan.maxPlayers()));
        environment.put("ONIBRIDGE_FORWARDING_SECRET", tenant.forwardingSecret());
        environment.put("ONIBRIDGE_SURVIVAL_SECRET", "");
        environment.put("ONIBRIDGE_JAVA_SECRET", "");
        environment.put("ONILINK_DASHBOARD_SETUP_CODE", tenant.setupCode());

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("external_id", "onilink-tenant-" + tenant.id());
        request.put("name", "OniLink - " + tenant.id());
        request.put("description", "Isolated OniLink proxy instance for tenant " + tenant.id() + ".");
        request.put("user", tenant.userId());
        request.put("egg", settings.eggId());
        request.put("docker_image", settings.dockerImage());
        request.put("startup", settings.startup());
        request.put("environment", environment);
        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("memory", plan.memory());
        limits.put("swap", plan.swap());
        limits.put("disk", plan.disk());
        limits.put("io", plan.io());
        limits.put("cpu", plan.cpu());
        limits.put("threads", plan.threads().isBlank() ? null : plan.threads());
        request.put("limits", limits);
        request.put("feature_limits", Map.of(
                "databases", plan.databases(),
                "allocations", 0,
                "backups", plan.backups()
        ));
        request.put("allocation", Map.of("default", tenant.allocationId()));
        request.put("skip_scripts", false);
        request.put("oom_disabled", false);
        return request;
    }

    private void writeHandoff(Tenant tenant) throws IOException {
        String instructions = """
                ONILINK TENANT HANDOFF

                Tenant: %s
                Customer: %s
                Isolation: one dedicated Pterodactyl server; no other tenant backends are present.
                Player address: %s
                Dashboard: http://%s/
                One-time dashboard setup code: %s

                PORTS
                - The OniLink server uses one primary allocation. UDP is the Bedrock listener and TCP is the
                  dashboard on the same numeric port.
                - The BDS server keeps its own UDP allocation: %s
                - OniBridge needs no additional allocation.

                BACKEND INSTALL
                1. Install the matching OniBridge .so in /home/container/plugins/ on the tenant's BDS server.
                2. Upload backend/default.key and backend/onibridge.toml from this ZIP into:
                   /home/container/plugins/onibridge/
                3. Start BDS and confirm the native identity hook is active.
                4. Start OniLink and use the setup code above to create the tenant's dashboard owner.

                Treat this ZIP as a secret. It contains the backend forwarding key and setup code.
                """.formatted(tenant.id(), tenant.customerLabel(), tenant.proxyAddress(),
                tenant.proxyAddress(), tenant.setupCode(), tenant.backendAddress());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            zip(zip, "CUSTOMER-START-HERE.txt", instructions);
            zip(zip, "backend/default.key", tenant.forwardingSecret() + "\n");
            zip(zip, "backend/onibridge.toml", onibridgeToml(settings.bdsProfile(), tenant.trustedProxyCidr()));
        }
        atomicWrite(handoffPath(tenant.id()), bytes.toByteArray());
    }

    private static void zip(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String onibridgeToml(String profile, String trustedProxyCidr) {
        return """
                # Generated by the OniLink control plane for the isolated default backend.
                # Install as: /home/container/plugins/onibridge/onibridge.toml

                bridge_id = "default-main"
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
                """.formatted(trustedProxyCidr, profile);
    }

    private Settings loadSettings() throws IOException {
        if (!Files.isRegularFile(settingsPath)) return Settings.empty();
        Properties properties = loadProperties(settingsPath);
        int version = integer(properties.getProperty("version", "0"), "hosting storage version");
        if (version != STORAGE_VERSION) throw new IOException("Unsupported dashboard hosting settings version");
        return new Settings(
                properties.getProperty("panelUrl", ""),
                properties.getProperty("apiKey", ""),
                integer(properties.getProperty("eggId", "0"), "egg ID"),
                properties.getProperty("dockerImage", ""),
                properties.getProperty("startup", ""),
                properties.getProperty("onilinkVersion", "v0.1.5"),
                properties.getProperty("bdsProfile", "bds-1.26.44.3-linux-x86_64-06effdd00067f1ae"),
                properties.getProperty("updatedAt", "")
        );
    }

    private void loadPlans() throws IOException {
        if (!Files.isRegularFile(settingsPath)) return;
        Properties properties = loadProperties(settingsPath);
        for (String id : csv(properties.getProperty("plans", ""))) {
            String prefix = "plan." + id + ".";
            plans.put(id, new HostingPlan(
                    id,
                    properties.getProperty(prefix + "name", id),
                    integer(properties.getProperty(prefix + "memory", "1024"), "plan memory"),
                    integer(properties.getProperty(prefix + "swap", "0"), "plan swap"),
                    integer(properties.getProperty(prefix + "disk", "1024"), "plan disk"),
                    integer(properties.getProperty(prefix + "io", "500"), "plan I/O"),
                    integer(properties.getProperty(prefix + "cpu", "100"), "plan CPU"),
                    properties.getProperty(prefix + "threads", ""),
                    integer(properties.getProperty(prefix + "databases", "0"), "plan databases"),
                    0,
                    integer(properties.getProperty(prefix + "backups", "1"), "plan backups"),
                    integer(properties.getProperty(prefix + "maxPlayers", "20"), "plan players")
            ));
        }
    }

    private void loadTenants() throws IOException {
        if (!Files.isRegularFile(tenantsPath)) return;
        Properties properties = loadProperties(tenantsPath);
        int version = integer(properties.getProperty("version", "0"), "tenant storage version");
        if (version != STORAGE_VERSION) throw new IOException("Unsupported dashboard tenant storage version");
        for (String id : csv(properties.getProperty("tenants", ""))) {
            String prefix = "tenant." + id + ".";
            tenants.put(id, new Tenant(
                    id,
                    properties.getProperty(prefix + "customerLabel", id),
                    properties.getProperty(prefix + "planId", "starter"),
                    integer(properties.getProperty(prefix + "userId", "0"), "tenant user ID"),
                    properties.getProperty(prefix + "userDisplay", ""),
                    integer(properties.getProperty(prefix + "nodeId", "0"), "tenant node ID"),
                    integer(properties.getProperty(prefix + "allocationId", "0"), "tenant allocation ID"),
                    properties.getProperty(prefix + "proxyAddress", ""),
                    properties.getProperty(prefix + "backendAddress", ""),
                    properties.getProperty(prefix + "trustedProxyCidr", ""),
                    properties.getProperty(prefix + "forwardingSecret", ""),
                    properties.getProperty(prefix + "setupCode", ""),
                    integer(properties.getProperty(prefix + "serverId", "0"), "tenant server ID"),
                    properties.getProperty(prefix + "uuid", ""),
                    Boolean.parseBoolean(properties.getProperty(prefix + "suspended", "false")),
                    properties.getProperty(prefix + "status", "unknown"),
                    properties.getProperty(prefix + "lastError", ""),
                    properties.getProperty(prefix + "createdAt", ""),
                    properties.getProperty(prefix + "updatedAt", "")
            ));
        }
    }

    private void saveSettings() throws IOException {
        Properties properties = new Properties();
        properties.setProperty("version", Integer.toString(STORAGE_VERSION));
        properties.setProperty("panelUrl", settings.panelUrl());
        properties.setProperty("apiKey", settings.apiKey());
        properties.setProperty("eggId", Integer.toString(settings.eggId()));
        properties.setProperty("dockerImage", settings.dockerImage());
        properties.setProperty("startup", settings.startup());
        properties.setProperty("onilinkVersion", settings.onilinkVersion());
        properties.setProperty("bdsProfile", settings.bdsProfile());
        properties.setProperty("updatedAt", settings.updatedAt());
        properties.setProperty("plans", String.join(",", plans.keySet()));
        for (HostingPlan plan : plans.values()) {
            String prefix = "plan." + plan.id() + ".";
            properties.setProperty(prefix + "name", plan.name());
            properties.setProperty(prefix + "memory", Integer.toString(plan.memory()));
            properties.setProperty(prefix + "swap", Integer.toString(plan.swap()));
            properties.setProperty(prefix + "disk", Integer.toString(plan.disk()));
            properties.setProperty(prefix + "io", Integer.toString(plan.io()));
            properties.setProperty(prefix + "cpu", Integer.toString(plan.cpu()));
            properties.setProperty(prefix + "threads", plan.threads());
            properties.setProperty(prefix + "databases", Integer.toString(plan.databases()));
            properties.setProperty(prefix + "backups", Integer.toString(plan.backups()));
            properties.setProperty(prefix + "maxPlayers", Integer.toString(plan.maxPlayers()));
        }
        storeProperties(settingsPath, properties, "OniLink owner-only Pterodactyl hosting settings");
    }

    private void saveTenants() throws IOException {
        Properties properties = new Properties();
        properties.setProperty("version", Integer.toString(STORAGE_VERSION));
        properties.setProperty("tenants", String.join(",", tenants.keySet()));
        for (Tenant tenant : tenants.values()) {
            String prefix = "tenant." + tenant.id() + ".";
            properties.setProperty(prefix + "customerLabel", tenant.customerLabel());
            properties.setProperty(prefix + "planId", tenant.planId());
            properties.setProperty(prefix + "userId", Integer.toString(tenant.userId()));
            properties.setProperty(prefix + "userDisplay", tenant.userDisplay());
            properties.setProperty(prefix + "nodeId", Integer.toString(tenant.nodeId()));
            properties.setProperty(prefix + "allocationId", Integer.toString(tenant.allocationId()));
            properties.setProperty(prefix + "proxyAddress", tenant.proxyAddress());
            properties.setProperty(prefix + "backendAddress", tenant.backendAddress());
            properties.setProperty(prefix + "trustedProxyCidr", tenant.trustedProxyCidr());
            properties.setProperty(prefix + "forwardingSecret", tenant.forwardingSecret());
            properties.setProperty(prefix + "setupCode", tenant.setupCode());
            properties.setProperty(prefix + "serverId", Integer.toString(tenant.serverId()));
            properties.setProperty(prefix + "uuid", tenant.uuid());
            properties.setProperty(prefix + "suspended", Boolean.toString(tenant.suspended()));
            properties.setProperty(prefix + "status", tenant.status());
            properties.setProperty(prefix + "lastError", tenant.lastError());
            properties.setProperty(prefix + "createdAt", tenant.createdAt());
            properties.setProperty(prefix + "updatedAt", tenant.updatedAt());
        }
        storeProperties(tenantsPath, properties, "OniLink owner-only tenant records and handoff secrets");
    }

    private PterodactylApplicationClient client() {
        if (settings.panelUrl().isBlank() || settings.apiKey().isBlank()) {
            throw new IllegalStateException("Save the Pterodactyl panel URL and application API key first");
        }
        return new PterodactylApplicationClient(settings.panelUrl(), settings.apiKey());
    }

    private void requireConfigured() {
        if (!settings.configured()) {
            throw new IllegalStateException("Save the Pterodactyl connection and OniLink egg settings first");
        }
    }

    private Path handoffPath(String tenantId) {
        return handoffDirectory.resolve(tenantId + ".handoff.zip");
    }

    private String randomBase64(int bytes) {
        byte[] value = new byte[bytes];
        random.nextBytes(value);
        return Base64.getEncoder().encodeToString(value);
    }

    private String randomUrlToken(int bytes) {
        byte[] value = new byte[bytes];
        random.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
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
            // Windows inherits ACLs from the dashboard directory; POSIX deployments get explicit 0600.
        }
    }

    private static String normalizedPanelUrl(String raw) {
        String value = required(raw, "Panel URL", 2_000);
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Panel URL is invalid");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("Panel URL must be a plain HTTPS origin");
        }
        String path = value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
        if (!URI.create(path).getPath().matches("/?")) {
            throw new IllegalArgumentException("Panel URL must not include a path");
        }
        return path;
    }

    private static String slug(String raw, String label) {
        String value = required(raw, label, 32).toLowerCase(Locale.ROOT);
        if (!SLUG.matcher(value).matches()) {
            throw new IllegalArgumentException(label + " must be 2-32 lowercase letters, numbers, or hyphens and start with a letter");
        }
        return value;
    }

    private static String required(String raw, String label, int maximum) {
        String value = value(raw).trim();
        if (value.isBlank()) throw new IllegalArgumentException(label + " is required");
        if (value.length() > maximum) throw new IllegalArgumentException(label + " is too long");
        return value;
    }

    private static String safeIdentifier(String raw, String label, int maximum) {
        String value = required(raw, label, maximum);
        if (!value.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException(label + " may contain only letters, numbers, periods, underscores, and hyphens");
        }
        return value;
    }

    private static String optional(String raw, int maximum) {
        String value = value(raw).trim();
        if (value.length() > maximum) throw new IllegalArgumentException("Value is too long");
        return value;
    }

    private static int positiveInt(String raw, String label) {
        return boundedInt(raw, label, 1, Integer.MAX_VALUE);
    }

    private static int boundedInt(String raw, String label, int minimum, int maximum) {
        return boundedIntDefault(raw, label, Integer.MIN_VALUE, minimum, maximum);
    }

    private static int boundedIntDefault(String raw, String label, int fallback, int minimum, int maximum) {
        String value = value(raw).trim();
        if (value.isBlank() && fallback != Integer.MIN_VALUE) return fallback;
        int parsed = integer(value, label);
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
        String value = raw.trim();
        String host;
        String portText;
        if (value.startsWith("[")) {
            int close = value.indexOf(']');
            if (close < 2 || close + 2 > value.length() || value.charAt(close + 1) != ':') {
                throw new IllegalArgumentException("Backend address must be host:port or [IPv6]:port");
            }
            host = value.substring(1, close);
            portText = value.substring(close + 2);
        } else {
            int separator = value.lastIndexOf(':');
            if (separator < 1 || value.indexOf(':') != separator) {
                throw new IllegalArgumentException("Backend address must be host:port or [IPv6]:port");
            }
            host = value.substring(0, separator);
            portText = value.substring(separator + 1);
        }
        if (host.isBlank() || host.length() > 253) throw new IllegalArgumentException("Backend host is invalid");
        int port = boundedInt(portText, "backend port", 1, 65_535);
        return new Endpoint(host, port);
    }

    private static String exactCidr(String raw) {
        String value = required(raw, "Proxy source IP", 128);
        if (!value.matches("[0-9a-fA-F:.]+")) {
            throw new IllegalArgumentException("Proxy source IP must be an IPv4 or IPv6 address, not a hostname");
        }
        try {
            InetAddress parsed = InetAddress.getByName(value);
            return parsed.getHostAddress() + (parsed.getAddress().length == 4 ? "/32" : "/128");
        } catch (IOException exception) {
            throw new IllegalArgumentException("Proxy source IP is invalid");
        }
    }

    private static String endpointAddress(Map<String, Object> allocation) {
        String host = text(allocation.get("alias"));
        if (host.isBlank()) host = text(allocation.get("ip"));
        if (host.contains(":") && !host.startsWith("[")) host = "[" + host + "]";
        return host + ":" + number(allocation.get("port"));
    }

    @SuppressWarnings("unchecked")
    private static String preferredDockerImage(Map<String, Object> egg) {
        Object images = egg.get("docker_images");
        if (images instanceof Map<?, ?> map && !map.isEmpty()) {
            return text(map.values().iterator().next());
        }
        if (images instanceof List<?> list && !list.isEmpty()) return text(list.get(0));
        return text(egg.get("docker_image"));
    }

    private static String displayName(Map<String, Object> user) {
        String full = (text(user.get("first_name")) + " " + text(user.get("last_name"))).trim();
        return full.isBlank() ? text(user.get("username")) : full;
    }

    private static String safeError(String message, Tenant tenant) {
        String value = message == null ? "Provisioning failed" : message;
        value = value.replace(tenant.forwardingSecret(), "[redacted]")
                .replace(tenant.setupCode(), "[redacted]");
        return value.substring(0, Math.min(value.length(), 500));
    }

    private static List<String> csv(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        List<String> values = new ArrayList<>();
        for (String value : raw.split(",")) if (!value.isBlank()) values.add(value.trim());
        return values;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    private static int number(Object value) {
        if (value instanceof Number number) return number.intValue();
        return integer(text(value), "API value");
    }

    private static boolean booleanValue(Object value) {
        return value instanceof Boolean bool ? bool : Boolean.parseBoolean(text(value));
    }

    private record Settings(
            String panelUrl,
            String apiKey,
            int eggId,
            String dockerImage,
            String startup,
            String onilinkVersion,
            String bdsProfile,
            String updatedAt
    ) {
        static Settings empty() {
            return new Settings("", "", 0, "ghcr.io/ptero-eggs/yolks:java_21",
                    "bash ./start-onilink.sh",
                    "v0.1.5", "bds-1.26.44.3-linux-x86_64-06effdd00067f1ae", "");
        }

        boolean configured() {
            return !panelUrl.isBlank() && !apiKey.isBlank() && eggId > 0
                    && !dockerImage.isBlank() && !startup.isBlank();
        }

        Map<String, Object> asMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("configured", configured());
            result.put("panelUrl", panelUrl);
            result.put("apiKeyConfigured", !apiKey.isBlank());
            result.put("apiKeyHint", apiKey.isBlank() ? "" : "••••" + apiKey.substring(Math.max(0, apiKey.length() - 4)));
            result.put("eggId", eggId);
            result.put("dockerImage", dockerImage);
            result.put("startup", startup);
            result.put("onilinkVersion", onilinkVersion);
            result.put("bdsProfile", bdsProfile);
            result.put("updatedAt", updatedAt);
            return result;
        }
    }

    private record HostingPlan(
            String id,
            String name,
            int memory,
            int swap,
            int disk,
            int io,
            int cpu,
            String threads,
            int databases,
            int allocations,
            int backups,
            int maxPlayers
    ) {
        Map<String, Object> asMap() {
            return Map.ofEntries(
                    Map.entry("id", id), Map.entry("name", name),
                    Map.entry("memory", memory), Map.entry("swap", swap),
                    Map.entry("disk", disk), Map.entry("io", io),
                    Map.entry("cpu", cpu), Map.entry("threads", threads),
                    Map.entry("databases", databases), Map.entry("allocations", allocations),
                    Map.entry("backups", backups), Map.entry("maxPlayers", maxPlayers));
        }
    }

    private record Tenant(
            String id,
            String customerLabel,
            String planId,
            int userId,
            String userDisplay,
            int nodeId,
            int allocationId,
            String proxyAddress,
            String backendAddress,
            String trustedProxyCidr,
            String forwardingSecret,
            String setupCode,
            int serverId,
            String uuid,
            boolean suspended,
            String status,
            String lastError,
            String createdAt,
            String updatedAt
    ) {
        Map<String, Object> asMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", id);
            result.put("customerLabel", customerLabel);
            result.put("plan", planId);
            result.put("userId", userId);
            result.put("userDisplay", userDisplay);
            result.put("nodeId", nodeId);
            result.put("allocationId", allocationId);
            result.put("proxyAddress", proxyAddress);
            result.put("backendAddress", backendAddress);
            result.put("serverId", serverId);
            result.put("uuid", uuid);
            result.put("suspended", suspended);
            result.put("status", status);
            result.put("lastError", lastError);
            result.put("handoffAvailable", !forwardingSecret.isBlank() && !setupCode.isBlank());
            result.put("createdAt", createdAt);
            result.put("updatedAt", updatedAt);
            return result;
        }

        Tenant withRemote(Map<String, Object> remote) {
            boolean remoteSuspended = booleanValue(remote.get("suspended"));
            String remoteStatus = text(remote.get("status"));
            if (remoteStatus.isBlank() || "null".equals(remoteStatus)) {
                remoteStatus = remoteSuspended ? "suspended" : "active";
            }
            return new Tenant(id, customerLabel, planId, userId, userDisplay, nodeId,
                    allocationId, proxyAddress, backendAddress, trustedProxyCidr, forwardingSecret,
                    setupCode, number(remote.get("id")), text(remote.get("uuid")), remoteSuspended,
                    remoteStatus, "", createdAt, Instant.now().toString());
        }

        Tenant withState(String state, String error) {
            return new Tenant(id, customerLabel, planId, userId, userDisplay, nodeId,
                    allocationId, proxyAddress, backendAddress, trustedProxyCidr, forwardingSecret,
                    setupCode, serverId, uuid, suspended, state, error, createdAt, Instant.now().toString());
        }
    }

    private record Endpoint(String host, int port) {
        String display() {
            return (host.contains(":") ? "[" + host + "]" : host) + ":" + port;
        }
    }

}
