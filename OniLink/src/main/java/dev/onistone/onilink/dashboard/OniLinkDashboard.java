package dev.onistone.onilink.dashboard;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.onistone.onilink.config.DashboardConfig;
import dev.onistone.onilink.config.ProxyConfig;
import dev.onistone.onilink.listener.BedrockProxyListener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Embedded, authenticated HTTP dashboard shipped inside OniLink.jar. */
public final class OniLinkDashboard implements AutoCloseable {
    private static final String CONTENT_SECURITY_POLICY =
            "default-src 'self'; style-src 'self'; script-src 'self'; connect-src 'self'; "
                    + "img-src 'self' data:; base-uri 'none'; frame-ancestors 'none'; form-action 'self'";
    private static final int MAX_SUPPORT_FILE_BYTES = 2_097_152;

    private final DashboardConfig config;
    private final DashboardControl control;
    private final DashboardAccounts accounts;
    private final DashboardAuditLog audit;
    private final DashboardConfigFile configFile;
    private final DashboardTenantHosting tenantHosting;
    private final Path logPath;
    private final HttpServer server;
    private final ExecutorService requestExecutor;
    private final ScheduledExecutorService maintenanceExecutor;
    private final LoginLimiter loginLimiter = new LoginLimiter();
    private volatile boolean closed;

    OniLinkDashboard(
            DashboardConfig config,
            DashboardControl control,
            Path proxyConfigPath,
            Path logPath
    ) throws IOException {
        this(config, control, proxyConfigPath, logPath, DashboardTenantHosting.productionRuntimeFactory());
    }

    OniLinkDashboard(
            DashboardConfig config,
            DashboardControl control,
            Path proxyConfigPath,
            Path logPath,
            DashboardTenantHosting.RuntimeFactory tenantRuntimeFactory
    ) throws IOException {
        this.config = config;
        this.control = control;
        this.configFile = new DashboardConfigFile(proxyConfigPath);
        this.logPath = logPath;
        this.server = HttpServer.create(config.listenAddress(), 64);
        String publicAddress = displayAddress(server.getAddress());
        this.accounts = new DashboardAccounts(config.dataDirectory(), config.sessionMinutes(), publicAddress);
        this.audit = new DashboardAuditLog(config.dataDirectory());
        this.tenantHosting = new DashboardTenantHosting(
                config.dataDirectory(), accounts, tenantRuntimeFactory, providerPort(control));
        this.requestExecutor = Executors.newFixedThreadPool(8, runnable -> {
            Thread thread = new Thread(runnable, "onilink-dashboard-http");
            thread.setDaemon(true);
            return thread;
        });
        this.maintenanceExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "onilink-dashboard-maintenance");
            thread.setDaemon(true);
            return thread;
        });
        server.createContext("/", this::handle);
        server.setExecutor(requestExecutor);
        maintenanceExecutor.scheduleWithFixedDelay(accounts::cleanupSessions, 1, 1, TimeUnit.MINUTES);
        server.start();
        System.out.printf("OniLink dashboard listening on %s/TCP.%n", displayAddress(server.getAddress()));
        if (isWildcard(config.listenAddress().getAddress())) {
            System.out.println("WARNING: OniLink dashboard accepts remote HTTP connections. Put it behind HTTPS "
                    + "and restrict network access before using it across the Internet.");
        }
    }

    public static OniLinkDashboard start(
            Path proxyConfigPath,
            Path logPath,
            ProxyConfig proxyConfig,
            BedrockProxyListener listener
    ) throws IOException {
        if (!proxyConfig.dashboard().enabled()) return null;
        DashboardControl control = new ProxyDashboardControl(proxyConfig, listener);
        try {
            return new OniLinkDashboard(proxyConfig.dashboard(), control, proxyConfigPath, logPath);
        } catch (IOException | RuntimeException exception) {
            control.close();
            throw exception;
        }
    }

    int port() {
        return server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
        securityHeaders(exchange.getResponseHeaders());
        try {
            String path = exchange.getRequestURI().getPath();
            if (staticResource(exchange, path)) return;
            if ("/health".equals(path)) {
                requireMethod(exchange, "GET");
                sendJson(exchange, 200, Map.of("ok", true, "timestamp", Instant.now().toString()));
                return;
            }
            if ("/api/setup/status".equals(path)) {
                requireMethod(exchange, "GET");
                sendJson(exchange, 200, Map.of(
                        "setupRequired", accounts.setupRequired(),
                        "minimumPasswordLength", 12,
                        "setupFile", accounts.setupPath().getFileName().toString()
                ));
                return;
            }
            if ("/api/setup".equals(path)) {
                requireMutation(exchange, "POST");
                handleSetup(exchange);
                return;
            }
            if ("/api/login".equals(path)) {
                requireMutation(exchange, "POST");
                handleLogin(exchange);
                return;
            }

            String token = bearer(exchange);
            DashboardAccounts.Principal principal = accounts.authenticate(token)
                    .orElseThrow(() -> new HttpFailure(401, "Unauthorized"));
            routeAuthenticated(exchange, path, token, principal);
        } catch (HttpFailure failure) {
            sendError(exchange, failure.status, failure.getMessage());
        } catch (IllegalArgumentException failure) {
            sendError(exchange, 400, failure.getMessage());
        } catch (SecurityException failure) {
            sendError(exchange, 401, failure.getMessage());
        } catch (IllegalStateException failure) {
            sendError(exchange, 409, failure.getMessage());
        } catch (Exception failure) {
            System.err.printf("OniLink dashboard request failed for %s: %s%n",
                    exchange.getRequestURI(), failure);
            sendError(exchange, 500, "Dashboard request failed");
        } finally {
            exchange.close();
        }
    }

    private void routeAuthenticated(
            HttpExchange exchange,
            String path,
            String token,
            DashboardAccounts.Principal principal
    ) throws Exception {
        switch (path) {
            case "/api/logout" -> {
                requireMutation(exchange, "POST");
                accounts.logout(token);
                audit(exchange, principal, "session.logout", "success", Map.of());
                sendJson(exchange, 200, Map.of("loggedOut", true));
            }
            case "/api/whoami" -> {
                requireMethod(exchange, "GET");
                sendJson(exchange, 200, principal.asMap());
            }
            case "/api/state" -> {
                requireRole(principal, DashboardAccounts.Role.VIEWER);
                requireMethod(exchange, "GET");
                Map<String, Object> state = new LinkedHashMap<>(control.state());
                state.put("principal", principal.asMap());
                state.put("dashboard", Map.of(
                        "address", displayAddress(server.getAddress()),
                        "setupRequired", accounts.setupRequired(),
                        "configurationRevision", configFile.read().get("revision")
                ));
                sendJson(exchange, 200, state);
            }
            case "/api/players" -> {
                requireRole(principal, DashboardAccounts.Role.VIEWER);
                requireMethod(exchange, "GET");
                sendJson(exchange, 200, Map.of("players",
                        control.players(principal.role().allows(DashboardAccounts.Role.OPERATOR))));
            }
            case "/api/backends" -> {
                requireRole(principal, DashboardAccounts.Role.VIEWER);
                requireMethod(exchange, "GET");
                sendJson(exchange, 200, Map.of("backends",
                        control.backends(principal.role().allows(DashboardAccounts.Role.ADMIN))));
            }
            case "/api/packets" -> handlePacketMonitor(exchange, principal);
            case "/api/allowlist" -> handleAllowlist(exchange, principal);
            case "/api/logs" -> {
                requireRole(principal, DashboardAccounts.Role.OPERATOR);
                requireMethod(exchange, "GET");
                int limit = intQuery(exchange, "limit", config.logTailLines(), 50, config.logTailLines());
                sendJson(exchange, 200, Map.of("lines",
                        DashboardAuditLog.tail(logPath, limit, MAX_SUPPORT_FILE_BYTES)));
            }
            case "/api/audit" -> {
                requireRole(principal, DashboardAccounts.Role.ADMIN);
                requireMethod(exchange, "GET");
                int limit = intQuery(exchange, "limit", 250, 1, 1_000);
                sendJson(exchange, 200, Map.of("lines", audit.recent(limit)));
            }
            case "/api/config" -> {
                requireRole(principal, DashboardAccounts.Role.ADMIN);
                if ("GET".equals(exchange.getRequestMethod())) {
                    sendJson(exchange, 200, configFile.read());
                } else {
                    requireMutation(exchange, "POST");
                    Map<String, String> form = form(exchange);
                    Map<String, Object> result = configFile.save(form.get("revision"), form.get("content"));
                    audit(exchange, principal, "configuration.save", "success",
                            Map.of("revision", result.get("revision")));
                    sendJson(exchange, 200, result);
                }
            }
            case "/api/config/rollback" -> {
                requireRole(principal, DashboardAccounts.Role.ADMIN);
                requireMutation(exchange, "POST");
                Map<String, Object> result = configFile.rollback();
                audit(exchange, principal, "configuration.rollback", "success",
                        Map.of("revision", result.get("revision")));
                sendJson(exchange, 200, result);
            }
            case "/api/config/backends" -> {
                requireRole(principal, DashboardAccounts.Role.ADMIN);
                requireMutation(exchange, "POST");
                Map<String, String> form = form(exchange);
                Map<String, Object> result = configFile.addBackend(form.get("revision"), form);
                audit(exchange, principal, "configuration.backend_add", "success", Map.of(
                        "backend", result.get("backendName"),
                        "restartRequired", result.get("restartRequired")));
                sendJson(exchange, 201, result);
            }
            case "/api/users" -> handleUsers(exchange, principal);
            case "/api/tenancy" -> handleTenancyOverview(exchange, principal);
            case "/api/tenancy/tenants" -> handleTenancyTenants(exchange, principal);
            case "/api/tenancy/users" -> handleTenancyUsers(exchange, principal);
            case "/api/tenancy/proxies" -> handleTenancyProxies(exchange, principal);
            case "/api/tenancy/tenant/action" -> handleTenancyTenantAction(exchange, principal);
            case "/api/tenancy/proxy/action" -> handleTenancyProxyAction(exchange, principal);
            case "/api/tenancy/proxy" -> handleTenantProxyDashboard(exchange, principal);
            case "/api/tenancy/proxy/runtime" -> handleTenantProxyRuntime(exchange, principal);
            case "/api/tenancy/proxy/allowlist" -> handleTenantProxyAllowlist(exchange, principal);
            case "/api/tenancy/proxy/backends" -> handleTenantProxyBackends(exchange, principal);
            case "/api/tenancy/handoff" -> handleTenancyHandoff(exchange, principal);
            case "/api/account/password" -> handlePassword(exchange, principal);
            case "/api/account/totp/begin" -> {
                requireMutation(exchange, "POST");
                sendJson(exchange, 200, accounts.beginTotp(principal.username()).asMap());
            }
            case "/api/account/totp/enable" -> handleTotpEnable(exchange, principal);
            case "/api/account/totp/disable" -> handleTotpDisable(exchange, principal);
            case "/api/action/transfer" -> handleAction(exchange, principal, "player.transfer");
            case "/api/action/disconnect" -> handleAction(exchange, principal, "player.disconnect");
            case "/api/action/alert" -> handleAction(exchange, principal, "network.alert");
            case "/api/action/trace" -> handleAction(exchange, principal, "player.trace");
            case "/api/support-bundle" -> {
                requireRole(principal, DashboardAccounts.Role.ADMIN);
                requireMethod(exchange, "GET");
                audit(exchange, principal, "support.download", "success", Map.of());
                sendSupportBundle(exchange);
            }
            case "/api/shutdown" -> {
                requireRole(principal, DashboardAccounts.Role.OWNER);
                requireMutation(exchange, "POST");
                audit(exchange, principal, "proxy.shutdown", "success", Map.of());
                sendJson(exchange, 202, Map.of("accepted", true, "message", "OniLink is shutting down"));
                Thread shutdown = new Thread(control::shutdown, "onilink-dashboard-shutdown");
                shutdown.setDaemon(true);
                shutdown.start();
            }
            case "/metrics" -> {
                requireRole(principal, DashboardAccounts.Role.VIEWER);
                requireMethod(exchange, "GET");
                sendMetrics(exchange);
            }
            default -> throw new HttpFailure(404, "Not found");
        }
    }

    private void handleSetup(HttpExchange exchange) throws IOException {
        if (!accounts.setupRequired()) throw new HttpFailure(409, "Owner setup is already complete");
        Map<String, String> form = form(exchange);
        try {
            DashboardAccounts.BrowserSession session = accounts.setupOwner(
                    form.get("setupCode"), form.get("username"), form.get("password"));
            loginLimiter.success(remoteAddress(exchange));
            audit(exchange, session.principal(), "owner.setup", "success", Map.of());
            sendJson(exchange, 201, session.asMap());
        } catch (SecurityException exception) {
            loginLimiter.failure(remoteAddress(exchange));
            audit(exchange, null, "owner.setup", "denied", Map.of());
            throw exception;
        }
    }

    private void handleLogin(HttpExchange exchange) throws IOException {
        String remote = remoteAddress(exchange);
        if (!loginLimiter.allowed(remote)) throw new HttpFailure(429, "Too many login attempts; try again later");
        if (accounts.setupRequired()) throw new HttpFailure(409, "Owner setup required");
        Map<String, String> form = form(exchange);
        DashboardAccounts.LoginResult result = accounts.login(
                form.get("username"), form.get("password"), form.get("totp"));
        if (!result.success()) {
            loginLimiter.failure(remote);
            audit(exchange, null, "session.login", "denied", Map.of("totpRequired", result.totpRequired()));
            sendJson(exchange, 401, Map.of("error", result.error(), "totpRequired", result.totpRequired()));
            return;
        }
        loginLimiter.success(remote);
        audit(exchange, result.session().principal(), "session.login", "success", Map.of());
        sendJson(exchange, 200, result.session().asMap());
    }

    private void handleUsers(HttpExchange exchange, DashboardAccounts.Principal principal) throws IOException {
        requireRole(principal, DashboardAccounts.Role.OWNER);
        if ("GET".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 200, Map.of("users", accounts.users().stream()
                    .map(DashboardAccounts.UserView::asMap).toList()));
            return;
        }
        requireMutation(exchange, "POST", "DELETE");
        Map<String, String> form = form(exchange);
        if ("DELETE".equals(exchange.getRequestMethod())) {
            accounts.deleteUser(principal.username(), form.get("username"));
            audit(exchange, principal, "account.delete", "success", Map.of("username", form.get("username")));
            sendJson(exchange, 200, Map.of("deleted", true));
        } else {
            DashboardAccounts.Role role = DashboardAccounts.Role.parse(form.get("role"));
            accounts.createUser(form.get("username"), role, form.get("password"));
            audit(exchange, principal, "account.create", "success",
                    Map.of("username", form.get("username"), "role", role.wireName()));
            sendJson(exchange, 201, Map.of("created", true));
        }
    }

    private void handleTenancyOverview(
            HttpExchange exchange,
            DashboardAccounts.Principal principal
    ) throws IOException {
        requireMethod(exchange, "GET");
        String scope = principal.tenantScoped() ? principal.tenantId() : "";
        if (!principal.tenantScoped()) requireRole(principal, DashboardAccounts.Role.OWNER);
        sendJson(exchange, 200, tenantHosting.overview(scope));
    }

    private void handleTenancyTenants(
            HttpExchange exchange,
            DashboardAccounts.Principal principal
    ) throws IOException {
        requireRole(principal, DashboardAccounts.Role.OWNER);
        requireMutation(exchange, "POST");
        Map<String, String> values = form(exchange);
        Map<String, Object> result = tenantHosting.createTenant(values);
        audit(exchange, principal, "tenancy.tenant_create", "success",
                Map.of("tenant", value(values.get("tenant")), "username", value(values.get("username"))));
        sendJson(exchange, 201, result);
    }

    private void handleTenancyUsers(
            HttpExchange exchange,
            DashboardAccounts.Principal principal
    ) throws IOException {
        requireRole(principal, DashboardAccounts.Role.OWNER);
        requireMutation(exchange, "POST");
        Map<String, String> values = form(exchange);
        Map<String, Object> result = tenantHosting.addTenantUser(values);
        audit(exchange, principal, "tenancy.user_create", "success",
                Map.of("tenant", value(values.get("tenant")), "username", value(values.get("username"))));
        sendJson(exchange, 201, result);
    }

    private void handleTenancyProxies(
            HttpExchange exchange,
            DashboardAccounts.Principal principal
    ) throws IOException {
        requireRole(principal, DashboardAccounts.Role.OWNER);
        requireMutation(exchange, "POST");
        Map<String, String> values = form(exchange);
        Map<String, Object> result = tenantHosting.createProxy(values);
        audit(exchange, principal, "tenancy.proxy_create", "success", Map.of(
                "tenant", value(values.get("tenant")),
                "proxy", value(values.get("proxy")),
                "port", value(values.get("port"))));
        sendJson(exchange, 201, result);
    }

    private void handleTenancyTenantAction(
            HttpExchange exchange,
            DashboardAccounts.Principal principal
    ) throws IOException {
        requireRole(principal, DashboardAccounts.Role.OWNER);
        requireMutation(exchange, "POST");
        Map<String, String> values = form(exchange);
        Map<String, Object> result = tenantHosting.tenantAction(values.get("tenant"), values.get("action"));
        audit(exchange, principal, "tenancy.tenant_" + value(values.get("action")), "success",
                Map.of("tenant", value(values.get("tenant"))));
        sendJson(exchange, 200, result);
    }

    private void handleTenancyProxyAction(
            HttpExchange exchange,
            DashboardAccounts.Principal principal
    ) throws IOException {
        requireMutation(exchange, "POST");
        Map<String, String> values = form(exchange);
        String tenant = authorizedTenant(principal, values.get("tenant"));
        Map<String, Object> result = tenantHosting.proxyAction(tenant, values.get("proxy"), values.get("action"));
        audit(exchange, principal, "tenancy.proxy_" + value(values.get("action")), "success", Map.of(
                "tenant", tenant, "proxy", value(values.get("proxy"))));
        sendJson(exchange, 200, result);
    }

    private void handleTenantProxyDashboard(
            HttpExchange exchange,
            DashboardAccounts.Principal principal
    ) throws IOException {
        requireMethod(exchange, "GET");
        Map<String, String> values = query(exchange);
        String tenant = authorizedTenant(principal, values.get("tenant"));
        sendJson(exchange, 200, tenantHosting.proxyDashboard(tenant, values.get("proxy")));
    }

    private void handleTenantProxyRuntime(
            HttpExchange exchange,
            DashboardAccounts.Principal principal
    ) throws IOException {
        requireMutation(exchange, "POST");
        Map<String, String> values = form(exchange);
        String tenant = authorizedTenant(principal, values.get("tenant"));
        values.put("tenant", tenant);
        Map<String, Object> result = tenantHosting.runtimeAction(values);
        audit(exchange, principal, "tenancy.runtime_" + value(values.get("action")),
                Boolean.TRUE.equals(result.get("success")) ? "success" : "rejected",
                Map.of("tenant", tenant, "proxy", value(values.get("proxy"))));
        sendJson(exchange, Boolean.TRUE.equals(result.get("success")) ? 200 : 409, result);
    }

    private void handleTenantProxyAllowlist(
            HttpExchange exchange,
            DashboardAccounts.Principal principal
    ) throws IOException {
        requireMethod(exchange, "GET", "POST", "DELETE");
        if (!"GET".equals(exchange.getRequestMethod())) requireMutation(exchange, "POST", "DELETE");
        Map<String, String> values = "GET".equals(exchange.getRequestMethod()) ? query(exchange) : form(exchange);
        String tenant = authorizedTenant(principal, values.get("tenant"));
        Map<String, Object> result = tenantHosting.allowlist(
                tenant, values.get("proxy"), exchange.getRequestMethod(), values);
        if (!"GET".equals(exchange.getRequestMethod())) {
            audit(exchange, principal, "tenancy.allowlist_"
                            + ("DELETE".equals(exchange.getRequestMethod()) ? "remove" : "add"),
                    "success", Map.of("tenant", tenant, "proxy", value(values.get("proxy")),
                            "xuid", value(values.get("xuid"))));
        }
        sendJson(exchange, 200, result);
    }

    private void handleTenantProxyBackends(
            HttpExchange exchange,
            DashboardAccounts.Principal principal
    ) throws IOException {
        requireMutation(exchange, "POST");
        Map<String, String> values = form(exchange);
        String tenant = authorizedTenant(principal, values.get("tenant"));
        values.put("tenant", tenant);
        Map<String, Object> result = tenantHosting.addBackend(values);
        audit(exchange, principal, "tenancy.backend_add", "success", Map.of(
                "tenant", tenant,
                "proxy", value(values.get("proxy")),
                "backend", value(values.get("name"))));
        sendJson(exchange, 201, result);
    }

    private void handlePacketMonitor(
            HttpExchange exchange,
            DashboardAccounts.Principal principal
    ) throws IOException {
        requireMethod(exchange, "GET");
        Map<String, String> filters = query(exchange);
        String requestedTenant = value(filters.get("tenant"));
        if (principal.tenantScoped() || !requestedTenant.isBlank()) {
            String tenant = authorizedTenant(principal, requestedTenant);
            sendJson(exchange, 200, tenantHosting.packetMonitor(
                    tenant,
                    filters.get("proxy"),
                    filters
            ));
            return;
        }
        requireRole(principal, DashboardAccounts.Role.VIEWER);
        sendJson(exchange, 200, control.packetMonitor(filters));
    }

    private void handleTenancyHandoff(
            HttpExchange exchange,
            DashboardAccounts.Principal principal
    ) throws IOException {
        requireMethod(exchange, "GET");
        Map<String, String> values = query(exchange);
        String tenant = authorizedTenant(principal, values.get("tenant"));
        String proxy = value(values.get("proxy"));
        byte[] payload = tenantHosting.handoff(tenant, proxy);
        audit(exchange, principal, "tenancy.handoff_download", "success",
                Map.of("tenant", tenant, "proxy", proxy));
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/zip");
        headers.set("Content-Disposition", "attachment; filename=" + tenant + "--" + proxy + ".handoff.zip");
        exchange.sendResponseHeaders(200, payload.length);
        exchange.getResponseBody().write(payload);
    }

    private void handleAllowlist(HttpExchange exchange, DashboardAccounts.Principal principal) throws IOException {
        requireRole(principal, DashboardAccounts.Role.ADMIN);
        if ("GET".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 200, control.allowlist());
            return;
        }
        requireMutation(exchange, "POST", "DELETE");
        Map<String, String> form = form(exchange);
        String xuid = value(form.get("xuid"));
        DashboardControl.ActionResult result;
        String action;
        if ("DELETE".equals(exchange.getRequestMethod())) {
            result = control.allowlistRemove(xuid);
            action = "allowlist.remove";
        } else {
            result = control.allowlistAdd(xuid, value(form.get("name")));
            action = "allowlist.add";
        }
        audit(exchange, principal, action, result.success() ? "success" : "rejected",
                Map.of("xuid", xuid, "message", result.message()));
        sendJson(exchange, result.success() ? 200 : 409, result.asMap());
    }

    private void handlePassword(HttpExchange exchange, DashboardAccounts.Principal principal) throws IOException {
        requireMutation(exchange, "POST");
        Map<String, String> form = form(exchange);
        accounts.changePassword(principal.username(), form.get("currentPassword"), form.get("newPassword"));
        audit(exchange, principal, "account.password_change", "success", Map.of());
        sendJson(exchange, 200, Map.of("changed", true, "sessionEnded", true));
    }

    private void handleTotpEnable(HttpExchange exchange, DashboardAccounts.Principal principal) throws IOException {
        requireMutation(exchange, "POST");
        Map<String, String> form = form(exchange);
        accounts.enableTotp(principal.username(), form.get("secret"), form.get("code"));
        audit(exchange, principal, "account.totp_enable", "success", Map.of());
        sendJson(exchange, 200, Map.of("enabled", true, "sessionEnded", true));
    }

    private void handleTotpDisable(HttpExchange exchange, DashboardAccounts.Principal principal) throws IOException {
        requireMutation(exchange, "POST");
        Map<String, String> form = form(exchange);
        accounts.disableTotp(principal.username(), form.get("password"), form.get("code"));
        audit(exchange, principal, "account.totp_disable", "success", Map.of());
        sendJson(exchange, 200, Map.of("disabled", true, "sessionEnded", true));
    }

    private void handleAction(
            HttpExchange exchange,
            DashboardAccounts.Principal principal,
            String action
    ) throws IOException {
        requireRole(principal, DashboardAccounts.Role.OPERATOR);
        requireMutation(exchange, "POST");
        Map<String, String> form = form(exchange);
        DashboardControl.ActionResult result = switch (action) {
            case "player.transfer" -> control.transfer(form.get("player"), form.get("backend"));
            case "player.disconnect" -> control.disconnect(form.get("player"), form.get("reason"));
            case "network.alert" -> control.alert(form.get("message"));
            case "player.trace" -> control.trace(form.get("player"), longValue(form.get("milliseconds"), 15_000));
            default -> throw new IllegalArgumentException("Unknown action");
        };
        audit(exchange, principal, action, result.success() ? "success" : "rejected", Map.of(
                "player", value(form.get("player")),
                "backend", value(form.get("backend")),
                "message", result.message()
        ));
        sendJson(exchange, result.success() ? 200 : 409, result.asMap());
    }

    private void sendSupportBundle(HttpExchange exchange) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            zip(zip, "state.json", DashboardJson.encode(control.state()));
            zip(zip, "players.json", DashboardJson.encode(control.players(false)));
            zip(zip, "backends.json", DashboardJson.encode(control.backends(false)));
            zip(zip, "packet-monitor.json", DashboardJson.encode(control.packetMonitor(Map.of(
                    "limit", "500",
                    "redactSensitive", "true"
            ))));
            zip(zip, "allowlist.json", DashboardJson.encode(control.allowlist()));
            zip(zip, "config.properties.redacted", String.valueOf(configFile.read().get("content")));
            zip(zip, "latest.log.tail", String.join(System.lineSeparator(),
                    DashboardAuditLog.tail(logPath, 2_000, MAX_SUPPORT_FILE_BYTES)));
            zip(zip, "audit.jsonl.tail", String.join(System.lineSeparator(), audit.recent(1_000)));
        }
        byte[] payload = bytes.toByteArray();
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/zip");
        headers.set("Content-Disposition", "attachment; filename=onilink-support-" + System.currentTimeMillis() + ".zip");
        exchange.sendResponseHeaders(200, payload.length);
        exchange.getResponseBody().write(payload);
    }

    private static void zip(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private void sendMetrics(HttpExchange exchange) throws IOException {
        Map<String, Object> state = control.state();
        String body = "# TYPE onilink_players gauge\n"
                + "onilink_players " + state.get("players") + "\n"
                + "# TYPE onilink_backends gauge\n"
                + "onilink_backends " + state.get("backends") + "\n"
                + "# TYPE onilink_uptime_milliseconds counter\n"
                + "onilink_uptime_milliseconds " + state.get("uptimeMillis") + "\n"
                + "# TYPE onilink_memory_used_bytes gauge\n"
                + "onilink_memory_used_bytes " + state.get("memoryUsedBytes") + "\n";
        send(exchange, 200, "text/plain; version=0.0.4; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    }

    private boolean staticResource(HttpExchange exchange, String path) throws IOException {
        if (path.equals("/health") || path.equals("/metrics") || path.startsWith("/api/")) return false;
        requireMethod(exchange, "GET");
        String rawPath = exchange.getRequestURI().getRawPath();
        if (rawPath == null || rawPath.indexOf('%') >= 0 || path.indexOf('\0') >= 0
                || path.indexOf('\\') >= 0 || path.contains("..")) {
            throw new HttpFailure(404, "Dashboard asset not found");
        }
        String resource;
        boolean html = false;
        if (path.equals("/") || path.equals("/index.html")) {
            resource = "/dashboard/index.html";
            html = true;
        } else if (path.startsWith("/assets/")
                && path.length() > "/assets/".length()
                && path.substring("/assets/".length()).matches("[A-Za-z0-9._-]+")) {
            resource = "/dashboard" + path;
        } else {
            throw new HttpFailure(404, "Dashboard asset not found");
        }
        try (InputStream input = OniLinkDashboard.class.getResourceAsStream(resource)) {
            if (input == null) throw new HttpFailure(404, "Dashboard asset not found");
            byte[] bytes = input.readAllBytes();
            String contentType = assetContentType(resource);
            if (!html && path.matches("/assets/.+-[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9]+")) {
                exchange.getResponseHeaders().set("Cache-Control", "public, max-age=31536000, immutable");
            } else {
                exchange.getResponseHeaders().set("Cache-Control", "no-store");
            }
            send(exchange, 200, contentType, bytes);
            return true;
        }
    }

    private static String assetContentType(String resource) {
        if (resource.endsWith(".html")) return "text/html; charset=utf-8";
        if (resource.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (resource.endsWith(".css")) return "text/css; charset=utf-8";
        if (resource.endsWith(".svg")) return "image/svg+xml; charset=utf-8";
        if (resource.endsWith(".json")) return "application/json; charset=utf-8";
        if (resource.endsWith(".woff")) return "font/woff";
        if (resource.endsWith(".woff2")) return "font/woff2";
        throw new HttpFailure(404, "Unsupported dashboard asset type");
    }

    private Map<String, String> form(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readNBytes(config.maxRequestBytes() + 1);
        if (bytes.length > config.maxRequestBytes()) throw new HttpFailure(413, "Request body is too large");
        String body = new String(bytes, StandardCharsets.UTF_8);
        Map<String, String> values = new LinkedHashMap<>();
        for (String pair : body.split("&")) {
            if (pair.isEmpty()) continue;
            String[] parts = pair.split("=", 2);
            String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            String value = parts.length == 2 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
            values.put(key, value);
        }
        return values;
    }

    private static void requireMethod(HttpExchange exchange, String... allowed) {
        String method = exchange.getRequestMethod();
        for (String candidate : allowed) if (candidate.equals(method)) return;
        throw new HttpFailure(405, "Method not allowed");
    }

    private static void requireMutation(HttpExchange exchange, String... allowed) {
        requireMethod(exchange, allowed);
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin == null || origin.isBlank()) return;
        String host = exchange.getRequestHeaders().getFirst("Host");
        try {
            URI parsed = URI.create(origin);
            if (host == null || !parsed.getRawAuthority().equalsIgnoreCase(host)) {
                throw new HttpFailure(403, "Cross-origin mutation rejected");
            }
        } catch (IllegalArgumentException exception) {
            throw new HttpFailure(403, "Invalid request origin");
        }
    }

    private static void requireRole(DashboardAccounts.Principal principal, DashboardAccounts.Role required) {
        if (!principal.role().allows(required)) throw new HttpFailure(403, "Insufficient role");
    }

    private static String authorizedTenant(DashboardAccounts.Principal principal, String requestedTenant) {
        String requested = value(requestedTenant).trim().toLowerCase(Locale.ROOT);
        if (principal.tenantScoped()) {
            if (!requested.isBlank() && !requested.equals(principal.tenantId())) {
                throw new HttpFailure(403, "Tenant account cannot access another tenant");
            }
            return principal.tenantId();
        }
        requireRole(principal, DashboardAccounts.Role.OWNER);
        return requested;
    }

    private static String bearer(HttpExchange exchange) {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) return "";
        return authorization.substring(7).trim();
    }

    private void audit(
            HttpExchange exchange,
            DashboardAccounts.Principal principal,
            String action,
            String result,
            Object details
    ) {
        audit.record(principal, remoteAddress(exchange), action, result, details);
    }

    private static String remoteAddress(HttpExchange exchange) {
        InetAddress address = exchange.getRemoteAddress().getAddress();
        return address == null ? exchange.getRemoteAddress().getHostString() : address.getHostAddress();
    }

    private static int intQuery(HttpExchange exchange, String name, int fallback, int minimum, int maximum) {
        String raw = query(exchange).get(name);
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Math.max(minimum, Math.min(Integer.parseInt(raw), maximum));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be a number");
        }
    }

    private static Map<String, String> query(HttpExchange exchange) {
        Map<String, String> values = new LinkedHashMap<>();
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null) return values;
        for (String pair : raw.split("&")) {
            String[] parts = pair.split("=", 2);
            values.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                    parts.length == 2 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "");
        }
        return values;
    }

    private static long longValue(String value, long fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("milliseconds must be a number");
        }
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    private static void securityHeaders(Headers headers) {
        headers.set("Cache-Control", "no-store");
        headers.set("Content-Security-Policy", CONTENT_SECURITY_POLICY);
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("X-Frame-Options", "DENY");
    }

    private static void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        send(exchange, status, "application/json; charset=utf-8",
                (DashboardJson.encode(body) + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private static void sendError(HttpExchange exchange, int status, String message) throws IOException {
        if (exchange.getResponseCode() != -1) return;
        sendJson(exchange, status, Map.of("error", message == null ? "Request failed" : message));
    }

    private static void send(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    private static String displayAddress(InetSocketAddress address) {
        String host = isWildcard(address.getAddress()) ? "<server-address>" : address.getHostString();
        if (host.indexOf(':') >= 0 && !host.startsWith("<")) host = "[" + host + "]";
        return "http://" + host + ":" + address.getPort();
    }

    private static int providerPort(DashboardControl control) {
        Object listener = control.state().get("listener");
        if (listener instanceof Map<?, ?> map) {
            Object port = map.get("port");
            if (port instanceof Number number) return number.intValue();
            try {
                return Integer.parseInt(String.valueOf(port));
            } catch (NumberFormatException ignored) {
                // Test controls and reduced integrations may not expose a listener address.
            }
        }
        return 0;
    }

    private static boolean isWildcard(InetAddress address) {
        return address != null && address.isAnyLocalAddress();
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        server.stop(1);
        maintenanceExecutor.shutdownNow();
        requestExecutor.shutdownNow();
        tenantHosting.close();
        control.close();
    }

    private static final class HttpFailure extends RuntimeException {
        private final int status;

        private HttpFailure(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private static final class LoginLimiter {
        private static final long WINDOW_MILLIS = TimeUnit.MINUTES.toMillis(5);
        private static final long BLOCK_MILLIS = TimeUnit.MINUTES.toMillis(15);
        private static final int MAX_FAILURES = 5;
        private final Map<String, AttemptWindow> attempts = new ConcurrentHashMap<>();

        boolean allowed(String address) {
            AttemptWindow window = attempts.get(address);
            return window == null || window.blockedUntil <= System.currentTimeMillis();
        }

        void success(String address) {
            attempts.remove(address);
        }

        void failure(String address) {
            attempts.compute(address, (key, current) -> {
                long now = System.currentTimeMillis();
                if (current == null || now - current.windowStarted > WINDOW_MILLIS) {
                    return new AttemptWindow(now, 1, 0);
                }
                int failures = current.failures + 1;
                return new AttemptWindow(current.windowStarted, failures,
                        failures >= MAX_FAILURES ? now + BLOCK_MILLIS : current.blockedUntil);
            });
        }

        private record AttemptWindow(long windowStarted, int failures, long blockedUntil) {
        }
    }
}
