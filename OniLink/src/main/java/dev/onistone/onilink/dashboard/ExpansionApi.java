package dev.onistone.onilink.dashboard;

import com.sun.net.httpserver.HttpExchange;
import dev.onistone.onilink.control.ControlJson;
import dev.onistone.onilink.platform.actions.ActionRegistry;
import dev.onistone.onilink.platform.persistence.PlatformDatabase;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Authenticated, tenant-scoped HTTP adapter for the expansion platform. */
final class ExpansionApi {
    private final ExpansionRuntime runtime;
    private final int maxRequestBytes;
    private final DashboardAuditLog audit;

    ExpansionApi(ExpansionRuntime runtime, int maxRequestBytes, DashboardAuditLog audit) {
        this.runtime = runtime;
        this.maxRequestBytes = maxRequestBytes;
        this.audit = audit;
    }

    boolean route(HttpExchange exchange, String path, DashboardAccounts.Principal principal) throws IOException {
        if (!isExpansionPath(path)) return false;
        Map<String, String> query = OniLinkDashboard.query(exchange);
        PlatformDatabase.Scope scope = scope(principal, query);

        if ("/api/modules".equals(path)) {
            require(principal, DashboardAccounts.Role.VIEWER);
            OniLinkDashboard.requireMethod(exchange, "GET");
            send(exchange, Map.of("modules", runtime.modules(), "eventBus", runtime.eventMetrics(),
                    "actions", runtime.actionDescriptors()));
            return true;
        }
        if ("/api/platform/actions".equals(path)) {
            require(principal, DashboardAccounts.Role.VIEWER);
            if ("GET".equals(exchange.getRequestMethod())) {
                send(exchange, Map.of("actions", runtime.actionDescriptors()));
            } else {
                OniLinkDashboard.requireMutation(exchange, "POST");
                Map<String, String> form = form(exchange);
                PlatformDatabase.Scope mutationScope = scope(principal, form);
                String action = required(form, "action").toUpperCase(Locale.ROOT);
                Map<String, Object> input = new LinkedHashMap<>(json(form.get("input")));
                if ("true".equalsIgnoreCase(form.get("confirmed"))) input.put("_confirmed", true);
                ActionRegistry.Context context = context(principal, mutationScope, form.get("correlationId"));
                ActionRegistry.Result result = runtime.actions().execute(action, context, input, form.get("idempotencyKey"));
                mutationAudit(exchange, principal, mutationScope, "platform.action." + action.toLowerCase(Locale.ROOT),
                        Map.of("correlationId", result.correlationId()));
                send(exchange, Map.of("action", result.action(), "correlationId", result.correlationId(),
                        "completedAt", result.completedAt().toString(), "result", result.value()));
            }
            return true;
        }

        if (path.startsWith("/api/flow/")) return flow(exchange, path, principal, scope);
        if (path.startsWith("/api/continuity/")) return continuity(exchange, path, principal, scope);
        if (path.startsWith("/api/security/quarantine")) return quarantine(exchange, principal, scope);
        if (path.startsWith("/api/journeys")) return journeys(exchange, principal, scope, query);
        if ("/api/protocols/diff".equals(path)) return protocolDiff(exchange, principal, query);
        if ("/api/compatibility/matrix".equals(path)) return compatibility(exchange, principal);
        if (path.startsWith("/api/fleet/")) return fleet(exchange, path, principal, scope);
        if ("/api/presence".equals(path)) return presence(exchange, principal, scope);
        if (path.startsWith("/api/roles")) return roles(exchange, path, principal, scope, query);
        if (path.startsWith("/api/support/tickets")) return support(exchange, path, principal, scope);
        if (path.startsWith("/api/packs/")) return packs(exchange, path, principal, scope);
        if (path.startsWith("/api/notifications/")) return notifications(exchange, path, principal, scope);
        throw new OniLinkDashboard.HttpFailure(404, "Not found");
    }

    private boolean flow(
            HttpExchange exchange, String path, DashboardAccounts.Principal principal, PlatformDatabase.Scope scope
    ) throws IOException {
        requireEnabled("flow");
        require(principal, DashboardAccounts.Role.VIEWER);
        if ("/api/flow/workflows".equals(path)) {
            if ("GET".equals(exchange.getRequestMethod())) {
                send(exchange, Map.of("workflows", runtime.flow().list(scope),
                        "executions", runtime.flow().executions(scope)));
            } else {
                require(principal, DashboardAccounts.Role.ADMIN);
                OniLinkDashboard.requireMutation(exchange, "POST");
                Map<String, String> form = form(exchange);
                scope = scope(principal, form);
                Map<String, Object> saved = runtime.flow().save(scope, json(form.get("workflow")), nullableLong(form.get("revision")));
                mutationAudit(exchange, principal, scope, "flow.workflow.save", Map.of("workflowId", saved.get("id")));
                send(exchange, saved);
            }
            return true;
        }
        if ("/api/flow/executions".equals(path)) {
            OniLinkDashboard.requireMethod(exchange, "GET");
            send(exchange, Map.of("executions", runtime.flow().executions(scope)));
            return true;
        }
        if (path.matches("/api/flow/workflows/[^/]+/(run|dry-run|internal-trigger)")) {
            String[] segments = path.split("/");
            String workflowId = segments[4];
            String action = segments[5];
            if ("dry-run".equals(action)) {
                OniLinkDashboard.requireMethod(exchange, "GET", "POST");
                send(exchange, runtime.flow().dryRun(scope, workflowId));
            } else {
                require(principal, DashboardAccounts.Role.OPERATOR);
                OniLinkDashboard.requireMutation(exchange, "POST");
                Map<String, String> form = form(exchange);
                scope = scope(principal, form);
                if ("internal-trigger".equals(action)) {
                    Map<String, Object> workflow = runtime.flow().list(scope).stream()
                            .filter(item -> workflowId.equals(item.get("id"))).findFirst()
                            .orElseThrow(() -> new OniLinkDashboard.HttpFailure(404, "Unknown workflow"));
                    if (!"WEBHOOK_INTERNAL".equals(workflow.get("trigger"))) {
                        throw new OniLinkDashboard.HttpFailure(409, "Workflow does not use WEBHOOK_INTERNAL");
                    }
                }
                Map<String, Object> result = runtime.flow().run(scope, workflowId,
                        context(principal, scope, form.get("correlationId")), form.get("idempotencyKey"));
                mutationAudit(exchange, principal, scope,
                        "internal-trigger".equals(action) ? "flow.execution.internal_trigger" : "flow.execution.start",
                        Map.of("workflowId", workflowId));
                send(exchange, result);
            }
            return true;
        }
        if (path.matches("/api/flow/executions/[^/]+/(cancel|approve)")) {
            require(principal, DashboardAccounts.Role.OPERATOR);
            OniLinkDashboard.requireMutation(exchange, "POST");
            String executionId = path.split("/")[4];
            boolean approve = path.endsWith("/approve");
            if (approve) require(principal, DashboardAccounts.Role.ADMIN);
            Map<String, Object> result = approve
                    ? runtime.flow().approve(scope, executionId, principal.username())
                    : runtime.flow().cancel(scope, executionId);
            mutationAudit(exchange, principal, scope, approve ? "flow.execution.approve" : "flow.execution.cancel",
                    Map.of("executionId", executionId));
            send(exchange, result);
            return true;
        }
        throw new OniLinkDashboard.HttpFailure(404, "Not found");
    }

    private boolean continuity(
            HttpExchange exchange, String path, DashboardAccounts.Principal principal, PlatformDatabase.Scope scope
    ) throws IOException {
        requireEnabled("continuity");
        require(principal, DashboardAccounts.Role.VIEWER);
        if ("/api/continuity/backends".equals(path)) {
            OniLinkDashboard.requireMethod(exchange, "GET");
            send(exchange, runtime.withScope(scope, () -> runtime.continuity().status(scope)));
            return true;
        }
        if (path.matches("/api/continuity/backends/[^/]+/(drain|return)")) {
            require(principal, DashboardAccounts.Role.ADMIN);
            OniLinkDashboard.requireMutation(exchange, "POST");
            String[] segments = path.split("/");
            String backend = segments[4];
            String action = segments[5];
            Map<String, Object> result = runtime.withScope(scope, () -> "drain".equals(action)
                    ? runtime.continuity().drain(scope, backend, principal.username())
                    : runtime.continuity().returnPlayers(scope, backend, principal.username()));
            mutationAudit(exchange, principal, scope, "continuity." + action, Map.of("backend", backend));
            send(exchange, result);
            return true;
        }
        throw new OniLinkDashboard.HttpFailure(404, "Not found");
    }

    private boolean quarantine(
            HttpExchange exchange, DashboardAccounts.Principal principal, PlatformDatabase.Scope scope
    ) throws IOException {
        requireEnabled("sentinel");
        require(principal, DashboardAccounts.Role.VIEWER);
        if ("GET".equals(exchange.getRequestMethod())) {
            send(exchange, Map.of("assignments", runtime.quarantine().list(scope)));
        } else {
            require(principal, DashboardAccounts.Role.ADMIN);
            OniLinkDashboard.requireMutation(exchange, "POST", "DELETE");
            Map<String, String> form = form(exchange);
            scope = scope(principal, form);
            Map<String, Object> result;
            if ("DELETE".equals(exchange.getRequestMethod())) {
                result = runtime.quarantine().release(scope, required(form, "xuid"));
            } else {
                PlatformDatabase.Scope selected = scope;
                result = runtime.withScope(selected, () -> runtime.quarantine().assign(selected,
                        required(form, "xuid"), required(form, "reason"), principal.username(), form.get("expiresAt")));
            }
            mutationAudit(exchange, principal, scope, "security.quarantine", Map.of("assignmentChanged", true));
            send(exchange, result);
        }
        return true;
    }

    private boolean journeys(
            HttpExchange exchange, DashboardAccounts.Principal principal, PlatformDatabase.Scope scope,
            Map<String, String> query
    ) throws IOException {
        requireEnabled("pulse");
        require(principal, DashboardAccounts.Role.VIEWER);
        OniLinkDashboard.requireMethod(exchange, "GET");
        boolean reveal = !principal.tenantScoped() && principal.role().allows(DashboardAccounts.Role.OPERATOR);
        send(exchange, runtime.withScope(scope, () -> runtime.journeys().snapshot(scope, reveal, query)));
        return true;
    }

    private boolean protocolDiff(
            HttpExchange exchange, DashboardAccounts.Principal principal, Map<String, String> query
    ) throws IOException {
        require(principal, DashboardAccounts.Role.ADMIN);
        OniLinkDashboard.requireMethod(exchange, "GET");
        send(exchange, runtime.forge().diff(integer(query.get("from")), integer(query.get("to"))));
        return true;
    }

    private boolean compatibility(HttpExchange exchange, DashboardAccounts.Principal principal) throws IOException {
        require(principal, DashboardAccounts.Role.VIEWER);
        OniLinkDashboard.requireMethod(exchange, "GET");
        send(exchange, runtime.forge().matrix());
        return true;
    }

    private boolean fleet(
            HttpExchange exchange, String path, DashboardAccounts.Principal principal, PlatformDatabase.Scope scope
    ) throws IOException {
        requireEnabled("fleet");
        require(principal, DashboardAccounts.Role.VIEWER);
        if ("/api/fleet/backends".equals(path)) {
            if ("GET".equals(exchange.getRequestMethod())) {
                PlatformDatabase.Scope selected = scope;
                send(exchange, runtime.withScope(selected, () -> runtime.fleet().snapshot(selected)));
            } else {
                require(principal, DashboardAccounts.Role.ADMIN);
                OniLinkDashboard.requireMutation(exchange, "POST", "PUT", "DELETE");
                Map<String, String> form = form(exchange);
                scope = scope(principal, form);
                PlatformDatabase.Scope selected = scope;
                Map<String, Object> result = runtime.withScope(selected, () -> switch (exchange.getRequestMethod()) {
                    case "POST" -> runtime.fleet().register(selected, form);
                    case "PUT" -> runtime.fleet().update(selected, form, longValue(form.get("recordRevision")));
                    case "DELETE" -> runtime.fleet().remove(selected, required(form, "name"),
                            longValue(form.get("runtimeRevision")), longValue(form.get("recordRevision")));
                    default -> throw new IllegalArgumentException("unsupported method");
                });
                mutationAudit(exchange, principal, selected, "fleet.backend." + exchange.getRequestMethod().toLowerCase(),
                        Map.of("backend", form.getOrDefault("name", "")));
                send(exchange, result);
            }
            return true;
        }
        if (path.matches("/api/fleet/backends/[^/]+/(validate|state|rollback)")) {
            String[] segments = path.split("/");
            String backend = URLDecoder.decode(segments[4], StandardCharsets.UTF_8);
            String operation = segments[5];
            if ("validate".equals(operation)) {
                OniLinkDashboard.requireMethod(exchange, "GET", "POST");
                if ("POST".equals(exchange.getRequestMethod())) {
                    OniLinkDashboard.requireMutation(exchange, "POST");
                }
                PlatformDatabase.Scope selected = scope;
                send(exchange, runtime.withScope(selected, () -> runtime.fleet().validateBackend(selected, backend)));
                return true;
            }
            require(principal, "rollback".equals(operation)
                    ? DashboardAccounts.Role.OWNER : DashboardAccounts.Role.ADMIN);
            OniLinkDashboard.requireMutation(exchange, "POST");
            Map<String, String> form = form(exchange);
            scope = scope(principal, form);
            if ("rollback".equals(operation)
                    && !Boolean.parseBoolean(form.getOrDefault("confirmed", "false"))) {
                throw new OniLinkDashboard.HttpFailure(409, "Registry rollback requires explicit confirmation");
            }
            PlatformDatabase.Scope selected = scope;
            Map<String, Object> result = runtime.withScope(selected, () -> "rollback".equals(operation)
                    ? runtime.fleet().rollbackBackend(selected, backend,
                            longValue(form.get("runtimeRevision")), nullableLong(form.get("recordRevision")))
                    : runtime.fleet().setEnabled(selected, backend,
                            Boolean.parseBoolean(required(form, "enabled")),
                            longValue(form.get("runtimeRevision")), longValue(form.get("recordRevision"))));
            mutationAudit(exchange, principal, selected, "fleet.backend." + operation,
                    Map.of("backend", backend));
            send(exchange, result);
            return true;
        }
        if ("/api/fleet/canaries".equals(path)) {
            require(principal, DashboardAccounts.Role.ADMIN);
            OniLinkDashboard.requireMutation(exchange, "POST");
            Map<String, String> form = form(exchange);
            scope = scope(principal, form);
            Map<String, Object> result = runtime.fleet().assignCanary(scope, required(form, "xuid"),
                    required(form, "backend"), integer(form.get("percentage")),
                    Boolean.parseBoolean(form.getOrDefault("testAccount", "false")),
                    form.getOrDefault("globalRole", ""));
            mutationAudit(exchange, principal, scope, "fleet.canary.assign", Map.of("backend", form.get("backend")));
            send(exchange, result);
            return true;
        }
        if ("/api/fleet/canaries/opt-out".equals(path)) {
            require(principal, DashboardAccounts.Role.OPERATOR);
            OniLinkDashboard.requireMutation(exchange, "POST");
            Map<String, String> form = form(exchange);
            scope = scope(principal, form);
            Map<String, Object> result = runtime.fleet().optOutCanary(scope, required(form, "xuid"));
            mutationAudit(exchange, principal, scope, "fleet.canary.opt_out", Map.of("changed", true));
            send(exchange, result);
            return true;
        }
        if ("/api/fleet/canaries/results".equals(path)) {
            require(principal, DashboardAccounts.Role.OPERATOR);
            OniLinkDashboard.requireMutation(exchange, "POST");
            Map<String, String> form = form(exchange);
            scope = scope(principal, form);
            Map<String, Object> result = runtime.fleet().recordCanaryResult(
                    scope, required(form, "xuid"), required(form, "backend"),
                    Boolean.parseBoolean(required(form, "success")), form.get("outcome"), form.get("journeyId"));
            mutationAudit(exchange, principal, scope, "fleet.canary.result",
                    Map.of("backend", form.get("backend"), "success", form.get("success")));
            send(exchange, result);
            return true;
        }
        if ("/api/fleet/deployments".equals(path)) {
            require(principal, DashboardAccounts.Role.ADMIN);
            OniLinkDashboard.requireMutation(exchange, "POST");
            Map<String, String> form = form(exchange);
            scope = scope(principal, form);
            Map<String, Object> result = runtime.fleet().saveDeployment(scope, json(form.get("deployment")),
                    nullableLong(form.get("revision")));
            mutationAudit(exchange, principal, scope, "fleet.deployment.save", Map.of("deploymentId", result.get("id")));
            send(exchange, result);
            return true;
        }
        if (path.matches("/api/fleet/deployments/[^/]+/(action|promote|rollback)")) {
            require(principal, DashboardAccounts.Role.OWNER);
            OniLinkDashboard.requireMutation(exchange, "POST");
            Map<String, String> form = form(exchange);
            String[] segments = path.split("/");
            String requested = switch (segments[5]) {
                case "promote" -> "PROMOTE_GREEN";
                case "rollback" -> "ROLLBACK_TO_BLUE";
                default -> required(form, "action");
            };
            PlatformDatabase.Scope selected = scope;
            Map<String, Object> result = runtime.withScope(selected, () -> runtime.fleet().deploymentAction(
                    selected, segments[4], requested, longValue(form.get("revision"))));
            mutationAudit(exchange, principal, selected, "fleet.deployment." + requested.toLowerCase(Locale.ROOT),
                    Map.of("deploymentId", segments[4]));
            send(exchange, result);
            return true;
        }
        throw new OniLinkDashboard.HttpFailure(404, "Not found");
    }

    private boolean presence(
            HttpExchange exchange, DashboardAccounts.Principal principal, PlatformDatabase.Scope scope
    ) throws IOException {
        requireEnabled("connect");
        require(principal, DashboardAccounts.Role.VIEWER);
        OniLinkDashboard.requireMethod(exchange, "GET");
        boolean reveal = !principal.tenantScoped() && principal.role().allows(DashboardAccounts.Role.OPERATOR);
        send(exchange, Map.of("presence", runtime.withScope(scope, () -> runtime.connect().presence(scope, reveal))));
        return true;
    }

    private boolean roles(
            HttpExchange exchange, String path, DashboardAccounts.Principal principal,
            PlatformDatabase.Scope scope, Map<String, String> query
    ) throws IOException {
        requireEnabled("connect");
        require(principal, DashboardAccounts.Role.VIEWER);
        if ("/api/roles".equals(path)) {
            if ("GET".equals(exchange.getRequestMethod())) {
                send(exchange, Map.of("roles", runtime.connect().roles(scope),
                        "assignments", runtime.connect().assignments(scope,
                                !principal.tenantScoped() && principal.role().allows(DashboardAccounts.Role.OPERATOR))));
            } else {
                require(principal, DashboardAccounts.Role.ADMIN);
                OniLinkDashboard.requireMutation(exchange, "POST");
                Map<String, String> form = form(exchange);
                scope = scope(principal, form);
                Map<String, Object> result = runtime.connect().saveRole(scope, json(form.get("role")),
                        nullableLong(form.get("revision")));
                mutationAudit(exchange, principal, scope, "roles.save", Map.of("roleId", result.get("id")));
                send(exchange, result);
            }
            return true;
        }
        if ("/api/roles/effective".equals(path)) {
            OniLinkDashboard.requireMethod(exchange, "GET");
            send(exchange, runtime.connect().effectivePermissions(scope, required(query, "xuid")));
            return true;
        }
        if ("/api/roles/assignments".equals(path)) {
            require(principal, DashboardAccounts.Role.ADMIN);
            OniLinkDashboard.requireMutation(exchange, "POST", "DELETE");
            Map<String, String> form = form(exchange);
            scope = scope(principal, form);
            Map<String, Object> result = "DELETE".equals(exchange.getRequestMethod())
                    ? runtime.connect().removeAssignment(scope, required(form, "assignmentId"), longValue(form.get("revision")))
                    : runtime.connect().assignRole(scope, required(form, "xuid"), required(form, "role"),
                            form.get("expiresAt"), principal.username());
            mutationAudit(exchange, principal, scope, "roles.assignment", Map.of("changed", true));
            send(exchange, result);
            return true;
        }
        throw new OniLinkDashboard.HttpFailure(404, "Not found");
    }

    private boolean support(
            HttpExchange exchange, String path, DashboardAccounts.Principal principal, PlatformDatabase.Scope scope
    ) throws IOException {
        requireEnabled("connect");
        require(principal, DashboardAccounts.Role.VIEWER);
        boolean manage = principal.tenantScoped() || principal.role().allows(DashboardAccounts.Role.OPERATOR);
        if ("/api/support/tickets".equals(path)) {
            if ("GET".equals(exchange.getRequestMethod())) {
                Map<String, String> query = OniLinkDashboard.query(exchange);
                String ownXuid = query.getOrDefault("xuid", "");
                if (!manage && ownXuid.isBlank()) throw new OniLinkDashboard.HttpFailure(403, "XUID is required for own-ticket view");
                send(exchange, Map.of("tickets", runtime.connect().tickets(scope, ownXuid, manage)));
            } else {
                OniLinkDashboard.requireMutation(exchange, "POST");
                Map<String, String> form = form(exchange);
                scope = scope(principal, form);
                Map<String, Object> result = runtime.connect().createTicket(scope, required(form, "xuid"),
                        form.get("displayLabel"), form.get("backend"), form.get("clientProtocol"),
                        form.get("category"), required(form, "message"), form.get("journeyId"),
                        Boolean.parseBoolean(form.getOrDefault("highPriority", "false")));
                mutationAudit(exchange, principal, scope, "support.ticket.create", Map.of("ticketId", result.get("id")));
                send(exchange, result);
            }
            return true;
        }
        if (path.matches("/api/support/tickets/[^/]+/(reply|status)")) {
            OniLinkDashboard.requireMutation(exchange, "POST");
            Map<String, String> form = form(exchange);
            scope = scope(principal, form);
            String ticketId = path.split("/")[4];
            Map<String, Object> result = runtime.connect().updateTicket(scope, ticketId,
                    longValue(form.get("revision")), form.get("status"), form.get("reply"),
                    principal.username(), form.getOrDefault("xuid", ""), manage);
            mutationAudit(exchange, principal, scope, "support.ticket.update", Map.of("ticketId", ticketId));
            send(exchange, result);
            return true;
        }
        throw new OniLinkDashboard.HttpFailure(404, "Not found");
    }

    private boolean packs(
            HttpExchange exchange, String path, DashboardAccounts.Principal principal, PlatformDatabase.Scope scope
    ) throws IOException {
        requireEnabled("packs");
        require(principal, DashboardAccounts.Role.ADMIN);
        if ("/api/packs/scans".equals(path)) {
            OniLinkDashboard.requireMethod(exchange, "GET");
            send(exchange, Map.of("scans", runtime.packs().history(scope)));
            return true;
        }
        if ("/api/packs/scan".equals(path)) {
            OniLinkDashboard.requireMutation(exchange, "POST");
            Map<String, String> form = form(exchange);
            scope = scope(principal, form);
            Map<String, Object> result = runtime.packs().scanBase64(scope,
                    required(form, "fileName"), required(form, "archiveBase64"));
            mutationAudit(exchange, principal, scope, "packs.scan", Map.of("scanId", result.get("id"),
                    "outcome", result.get("outcome")));
            send(exchange, result);
            return true;
        }
        throw new OniLinkDashboard.HttpFailure(404, "Not found");
    }

    private boolean notifications(
            HttpExchange exchange, String path, DashboardAccounts.Principal principal, PlatformDatabase.Scope scope
    ) throws IOException {
        requireEnabled("notifications");
        if ("/api/notifications/subscriptions".equals(path)) {
            if ("GET".equals(exchange.getRequestMethod())) {
                send(exchange, runtime.notifications().snapshot(scope, principal.username()));
            } else {
                OniLinkDashboard.requireMutation(exchange, "POST", "DELETE");
                Map<String, String> form = form(exchange);
                scope = scope(principal, form);
                Map<String, Object> result = "DELETE".equals(exchange.getRequestMethod())
                        ? runtime.notifications().revoke(scope, principal.username(),
                                required(form, "subscriptionId"), longValue(form.get("revision")),
                                principal.role().allows(DashboardAccounts.Role.ADMIN))
                        : runtime.notifications().subscribe(scope, principal.username(), json(form.get("subscription")));
                mutationAudit(exchange, principal, scope, "notifications.subscription", Map.of("changed", true));
                send(exchange, result);
            }
            return true;
        }
        if ("/api/notifications/test".equals(path)) {
            require(principal, DashboardAccounts.Role.ADMIN);
            OniLinkDashboard.requireMutation(exchange, "POST");
            send(exchange, runtime.notifications().test(scope, principal.username()));
            return true;
        }
        throw new OniLinkDashboard.HttpFailure(404, "Not found");
    }

    private Map<String, String> form(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readNBytes(maxRequestBytes + 1);
        if (bytes.length > maxRequestBytes) throw new OniLinkDashboard.HttpFailure(413, "Request body is too large");
        Map<String, String> values = new LinkedHashMap<>();
        String body = new String(bytes, StandardCharsets.UTF_8);
        for (String pair : body.split("&")) {
            if (pair.isEmpty()) continue;
            String[] parts = pair.split("=", 2);
            values.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                    parts.length == 2 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "");
        }
        return values;
    }

    private PlatformDatabase.Scope scope(DashboardAccounts.Principal principal, Map<String, String> values) {
        String requestedTenant = values.getOrDefault("tenant", "").trim().toLowerCase(Locale.ROOT);
        String requestedProxy = values.getOrDefault("proxy", "").trim().toLowerCase(Locale.ROOT);
        if (principal.tenantScoped()) {
            if (!requestedTenant.isBlank() && !principal.tenantId().equals(requestedTenant)) {
                throw new OniLinkDashboard.HttpFailure(403, "Tenant account cannot access another tenant");
            }
            if (requestedProxy.isBlank()) {
                requestedProxy = "main";
            }
            return PlatformDatabase.Scope.of(principal.tenantId(), requestedProxy);
        }
        if (!requestedTenant.isBlank() || !requestedProxy.isBlank()) {
            require(principal, DashboardAccounts.Role.OWNER);
            if (requestedTenant.isBlank() || requestedProxy.isBlank()) {
                throw new IllegalArgumentException("tenant and proxy are both required for scoped owner access");
            }
            return PlatformDatabase.Scope.of(requestedTenant, requestedProxy);
        }
        return PlatformDatabase.Scope.of("provider", "main");
    }

    private static ActionRegistry.Context context(
            DashboardAccounts.Principal principal, PlatformDatabase.Scope scope, String correlationId
    ) {
        String role = principal.tenantScoped() ? "admin" : principal.role().wireName();
        return new ActionRegistry.Context(principal.username(), role,
                scope.tenantId(), scope.proxyId(), correlationId);
    }

    private void mutationAudit(
            HttpExchange exchange, DashboardAccounts.Principal principal, PlatformDatabase.Scope scope,
            String action, Map<String, Object> summary
    ) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("tenant", scope.tenantId());
        details.put("proxy", scope.proxyId());
        details.putAll(summary);
        audit.record(principal, OniLinkDashboard.remoteAddress(exchange), action, "success", Map.copyOf(details));
    }

    private void requireEnabled(String module) {
        if (!runtime.enabled(module)) throw new OniLinkDashboard.HttpFailure(409, module + " module is disabled");
    }

    private static void require(DashboardAccounts.Principal principal, DashboardAccounts.Role role) {
        if (principal.tenantScoped()) {
            if (role == DashboardAccounts.Role.OWNER) {
                throw new OniLinkDashboard.HttpFailure(403, "This operation is provider-owner only");
            }
            return;
        }
        OniLinkDashboard.requireRole(principal, role);
    }

    private static boolean isExpansionPath(String path) {
        return path.equals("/api/modules") || path.startsWith("/api/platform/")
                || path.startsWith("/api/flow/") || path.startsWith("/api/continuity/")
                || path.startsWith("/api/security/quarantine") || path.startsWith("/api/journeys")
                || path.startsWith("/api/protocols/") || path.startsWith("/api/compatibility/")
                || path.startsWith("/api/fleet/") || path.equals("/api/presence")
                || path.startsWith("/api/roles") || path.startsWith("/api/support/tickets")
                || path.startsWith("/api/packs/") || path.startsWith("/api/notifications/");
    }

    private static Map<String, Object> json(String value) {
        return ControlJson.parseObject(value == null || value.isBlank() ? "{}" : value, 1_048_576);
    }

    private static String required(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(key + " is required");
        return value.trim();
    }
    private static int integer(String raw) {
        try { return Integer.parseInt(raw == null ? "" : raw); }
        catch (NumberFormatException failure) { throw new IllegalArgumentException("numeric value is invalid"); }
    }
    private static long longValue(String raw) {
        try { return Long.parseLong(raw == null ? "" : raw); }
        catch (NumberFormatException failure) { throw new IllegalArgumentException("revision is required and must be a number"); }
    }
    private static Long nullableLong(String raw) {
        return raw == null || raw.isBlank() ? null : longValue(raw);
    }
    private static void send(HttpExchange exchange, Object body) throws IOException {
        OniLinkDashboard.sendJson(exchange, 200, body);
    }
}
