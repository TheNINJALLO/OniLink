package dev.onistone.onilink.modules.fleet;

import dev.onistone.onilink.modules.ProxyOperations;
import dev.onistone.onilink.modules.ScopedRecords;
import dev.onistone.onilink.platform.events.BoundedEventBus;
import dev.onistone.onilink.platform.events.OniEvent;
import dev.onistone.onilink.platform.events.OniEventType;
import dev.onistone.onilink.platform.persistence.PlatformDatabase;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Runtime backend registry, sticky canaries, and auditable blue-green transitions. */
public final class FleetService extends ScopedRecords {
    private static final Set<String> DEPLOYMENT_ACTIONS = Set.of(
            "REGISTER_GREEN", "VALIDATE_GREEN", "START_CANARY", "STOP_CANARY", "PROMOTE_GREEN",
            "DRAIN_BLUE", "ROLLBACK_TO_BLUE", "RETIRE_BLUE");
    private final ProxyOperations proxy;
    private final BoundedEventBus events;
    private final int maxDynamicBackends;
    private final int maxCanaryPercentage;
    private final int assignmentMinutes;

    public FleetService(
            PlatformDatabase database, ProxyOperations proxy, BoundedEventBus events,
            int maxDynamicBackends, int maxCanaryPercentage, int assignmentMinutes
    ) {
        super(database);
        this.proxy = proxy;
        this.events = events;
        this.maxDynamicBackends = maxDynamicBackends;
        this.maxCanaryPercentage = maxCanaryPercentage;
        this.assignmentMinutes = assignmentMinutes;
    }

    public Map<String, Object> snapshot(PlatformDatabase.Scope scope) {
        return Map.of(
                "registry", proxy.backendRegistry(),
                "definitions", redactedDefinitions(database.list(scope, "dynamic-backend", maxDynamicBackends)),
                "canaries", views(database.list(scope, "canary", 10_000)),
                "canaryResults", views(database.list(scope, "canary-result", 10_000)),
                "deployments", views(database.list(scope, "deployment", 1_000)),
                "registryHistory", redactedDefinitions(database.list(scope, "dynamic-backend-history", 1_000)));
    }

    public Map<String, Object> register(PlatformDatabase.Scope scope, Map<String, String> values) {
        if (database.list(scope, "dynamic-backend", maxDynamicBackends + 1).size() >= maxDynamicBackends) {
            throw new IllegalStateException("dynamic backend limit reached");
        }
        Map<String, Object> runtime = proxy.registerBackend(values);
        String name = requiredString(values, "name").toLowerCase(Locale.ROOT);
        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put("name", name);
        stored.put("host", requiredString(values, "host"));
        stored.put("port", Long.parseLong(requiredString(values, "port")));
        stored.put("protocol", values.getOrDefault("protocol", "auto"));
        stored.put("proxyId", requiredString(values, "proxyId"));
        stored.put("bridgeId", requiredString(values, "bridgeId"));
        stored.put("keyId", requiredString(values, "keyId"));
        stored.put("secretReference", secretReference(values));
        stored.put("enabled", true);
        stored.put("runtimeRevision", runtime.get("revision"));
        stored.put("registeredAt", Instant.now().toString());
        Map<String, Object> result = redactRecord(view(database.put(scope, "dynamic-backend", name, null, stored)));
        events.publish(OniEvent.of(OniEventType.BACKEND_REGISTERED,
                scope.tenantId(), scope.proxyId(), Map.of("backend", name)));
        return result;
    }

    public Map<String, Object> update(
            PlatformDatabase.Scope scope, Map<String, String> values, long recordRevision
    ) {
        String name = requiredString(values, "name").toLowerCase(Locale.ROOT);
        PlatformDatabase.StoredRecord existing = database.get(scope, "dynamic-backend", name)
                .orElseThrow(() -> new IllegalArgumentException("unknown dynamic backend"));
        if (existing.revision() != recordRevision) {
            throw new PlatformDatabase.RevisionConflict(recordRevision, existing.revision());
        }
        saveHistory(scope, existing, "UPDATE");
        Map<String, Object> runtime = proxy.updateBackend(values);
        Map<String, Object> next = new LinkedHashMap<>(existing.value());
        next.put("host", requiredString(values, "host"));
        next.put("port", Long.parseLong(requiredString(values, "port")));
        next.put("protocol", values.getOrDefault("protocol", "auto"));
        next.put("secretReference", secretReference(values));
        next.put("runtimeRevision", runtime.get("revision"));
        Map<String, Object> result = redactRecord(view(database.put(scope, "dynamic-backend", name, recordRevision, next)));
        events.publish(OniEvent.of(OniEventType.BACKEND_UPDATED,
                scope.tenantId(), scope.proxyId(), Map.of("backend", name)));
        return result;
    }

    public Map<String, Object> remove(
            PlatformDatabase.Scope scope, String name, long runtimeRevision, long recordRevision
    ) {
        PlatformDatabase.StoredRecord existing = database.get(scope, "dynamic-backend", id(name))
                .orElseThrow(() -> new IllegalArgumentException("unknown dynamic backend"));
        saveHistory(scope, existing, "REMOVE");
        Map<String, String> values = Map.of("name", name, "revision", Long.toString(runtimeRevision));
        proxy.removeBackend(values);
        database.delete(scope, "dynamic-backend", existing.id(), recordRevision);
        return Map.of("removed", true, "backend", name);
    }

    public Map<String, Object> setEnabled(
            PlatformDatabase.Scope scope, String name, boolean enabled, long runtimeRevision, long recordRevision
    ) {
        PlatformDatabase.StoredRecord existing = database.get(scope, "dynamic-backend", id(name))
                .orElseThrow(() -> new IllegalArgumentException("unknown dynamic backend"));
        if (existing.revision() != recordRevision) {
            throw new PlatformDatabase.RevisionConflict(recordRevision, existing.revision());
        }
        Map<String, Object> runtime = proxy.setBackendEnabled(name, enabled, runtimeRevision);
        Map<String, Object> next = new LinkedHashMap<>(existing.value());
        next.put("enabled", enabled);
        next.put("runtimeRevision", runtime.get("revision"));
        return redactRecord(view(database.put(scope, "dynamic-backend", existing.id(), recordRevision, next)));
    }

    public Map<String, Object> validateBackend(PlatformDatabase.Scope scope, String name) {
        database.get(scope, "dynamic-backend", id(name))
                .orElseThrow(() -> new IllegalArgumentException("unknown dynamic backend"));
        Map<String, Object> backend = proxy.backends().stream()
                .filter(item -> name.equalsIgnoreCase(String.valueOf(item.get("name"))))
                .findFirst().orElseThrow(() -> new IllegalStateException("backend is absent from the live registry"));
        Map<?, ?> health = backend.get("health") instanceof Map<?, ?> map ? map : Map.of();
        Map<String, Object> bridge = bridgeStatus(name);
        boolean healthy = "online".equalsIgnoreCase(String.valueOf(health.get("status")));
        boolean protocolDetected = longValue(health.get("advertisedProtocol"), 0) > 0;
        boolean bridgeConnected = Boolean.TRUE.equals(bridge.get("connected"));
        return Map.of(
                "backend", name,
                "configuration", "VALID",
                "health", healthy ? "PASS" : "FAIL",
                "protocol", protocolDetected ? "PASS" : "PENDING",
                "advertisedProtocol", health.containsKey("advertisedProtocol") ? health.get("advertisedProtocol") : 0,
                "advertisedVersion", String.valueOf(
                        health.containsKey("advertisedVersion") ? health.get("advertisedVersion") : ""),
                "oniControl", bridgeConnected ? "PASS" : "UNAVAILABLE",
                "capabilityRevision", bridge.getOrDefault("capabilityRevision", 0));
    }

    public Map<String, Object> rollbackBackend(
            PlatformDatabase.Scope scope, String name, long runtimeRevision, Long recordRevision
    ) {
        PlatformDatabase.StoredRecord history = database.list(scope, "dynamic-backend-history", 1_000).stream()
                .filter(item -> name.equalsIgnoreCase(String.valueOf(item.value().get("backend"))))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("no previous registry revision exists"));
        Object rawDefinition = history.value().get("definition");
        if (!(rawDefinition instanceof Map<?, ?> definition)) {
            throw new IllegalStateException("registry history is invalid");
        }
        Map<String, String> values = definitionValues(definition, runtimeRevision);
        boolean exists = proxy.backends().stream()
                .anyMatch(item -> name.equalsIgnoreCase(String.valueOf(item.get("name"))));
        Map<String, Object> runtime = exists ? proxy.updateBackend(values) : proxy.registerBackend(values);
        Map<String, Object> restored = new LinkedHashMap<>();
        definition.forEach((key, value) -> restored.put(String.valueOf(key), value));
        restored.put("runtimeRevision", runtime.get("revision"));
        restored.put("rolledBackAt", Instant.now().toString());
        Map<String, Object> result = redactRecord(
                view(database.put(scope, "dynamic-backend", id(name), recordRevision, restored)));
        database.delete(scope, "dynamic-backend-history", history.id(), history.revision());
        return result;
    }

    public Map<String, Object> assignCanary(
            PlatformDatabase.Scope scope, String xuid, String backend, int percentage, boolean testAccount
    ) {
        return assignCanary(scope, xuid, backend, percentage, testAccount, "");
    }

    public Map<String, Object> assignCanary(
            PlatformDatabase.Scope scope, String xuid, String backend, int percentage,
            boolean testAccount, String globalRole
    ) {
        if (percentage < 0 || percentage > maxCanaryPercentage) {
            throw new IllegalArgumentException("canary percentage exceeds configured maximum");
        }
        String playerId = id(xuid);
        String candidate = id(backend).toLowerCase(Locale.ROOT);
        if (RoutingOverrides.reservedBackend(candidate)) {
            throw new IllegalArgumentException("limbo and quarantine backends cannot be canary candidates");
        }
        boolean roleEligible = globalRole == null || globalRole.isBlank() || database.list(
                scope, "role-assignment", 10_000).stream().anyMatch(assignment ->
                playerId.equals(assignment.value().get("xuid"))
                        && globalRole.equalsIgnoreCase(String.valueOf(assignment.value().get("role")))
                        && !expired(String.valueOf(assignment.value().getOrDefault("expiresAt", ""))));
        boolean quarantined = database.get(scope, "quarantine", playerId).isPresent();
        boolean selected = !quarantined && roleEligible && (testAccount
                || Math.floorMod((scope.tenantId() + ':' + playerId).hashCode(), 100) < percentage);
        Instant expiresAt = Instant.now().plusSeconds(assignmentMinutes * 60L);
        Map<String, Object> value = Map.of(
                "xuid", playerId,
                "backend", candidate,
                "percentage", percentage,
                "selected", selected,
                "testAccount", testAccount,
                "globalRole", globalRole == null ? "" : globalRole,
                "excludedByQuarantine", quarantined,
                "assignedAt", Instant.now().toString(),
                "expiresAt", expiresAt.toString());
        Map<String, Object> result = view(database.put(scope, "canary", playerId, null, value));
        RoutingOverrides.canary(scope, playerId, candidate, selected, expiresAt);
        events.publish(OniEvent.of(OniEventType.CANARY_ASSIGNED,
                scope.tenantId(), scope.proxyId(), Map.of("assignmentId", playerId, "selected", selected)));
        return result;
    }

    public Map<String, Object> optOutCanary(PlatformDatabase.Scope scope, String xuid) {
        String playerId = id(xuid);
        PlatformDatabase.StoredRecord existing = database.get(scope, "canary", playerId)
                .orElseThrow(() -> new IllegalArgumentException("unknown canary assignment"));
        Map<String, Object> next = new LinkedHashMap<>(existing.value());
        next.put("selected", false);
        next.put("optedOut", true);
        next.put("updatedAt", Instant.now().toString());
        RoutingOverrides.canary(scope, playerId, "", false);
        return view(database.put(scope, "canary", playerId, existing.revision(), next));
    }

    public Map<String, Object> recordCanaryResult(
            PlatformDatabase.Scope scope, String xuid, String backend, boolean success,
            String outcome, String journeyId
    ) {
        String playerId = id(xuid);
        PlatformDatabase.StoredRecord assignment = database.get(scope, "canary", playerId)
                .orElseThrow(() -> new IllegalArgumentException("player has no canary assignment"));
        if (!Boolean.TRUE.equals(assignment.value().get("selected"))) {
            throw new IllegalStateException("canary assignment is not active");
        }
        String candidate = id(backend).toLowerCase(Locale.ROOT);
        if (!candidate.equals(String.valueOf(assignment.value().get("backend")))) {
            throw new IllegalArgumentException("result backend does not match the sticky assignment");
        }
        String resultId = UUID.randomUUID().toString();
        Map<String, Object> value = Map.of(
                "xuid", playerId,
                "backend", candidate,
                "success", success,
                "outcome", bounded(outcome, 240),
                "journeyId", bounded(journeyId, 128),
                "recordedAt", Instant.now().toString());
        Map<String, Object> result = view(database.put(scope, "canary-result", resultId, null, value));
        events.publish(OniEvent.of(OniEventType.CANARY_RESULT_RECORDED,
                scope.tenantId(), scope.proxyId(), Map.of(
                        "resultId", resultId, "backend", candidate, "success", success,
                        "journeyId", bounded(journeyId, 128))));
        return result;
    }

    public Map<String, Object> saveDeployment(
            PlatformDatabase.Scope scope, Map<String, Object> input, Long revision
    ) {
        String deploymentId = id(input.get("id") == null ? null : String.valueOf(input.get("id")));
        String blue = required(input, "blueBackend", 64).toLowerCase(Locale.ROOT);
        String green = required(input, "greenBackend", 64).toLowerCase(Locale.ROOT);
        if (blue.equals(green)) throw new IllegalArgumentException("blue and green backends must differ");
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("name", required(input, "name", 120));
        value.put("blueBackend", blue);
        value.put("greenBackend", green);
        value.put("activeColor", String.valueOf(input.getOrDefault("activeColor", "BLUE")));
        value.put("candidateRevision", longValue(input.get("candidateRevision"), 1));
        value.put("healthGates", input.getOrDefault("healthGates", List.of()));
        value.put("canaryPolicy", input.getOrDefault("canaryPolicy", Map.of()));
        value.put("expectedProtocol", String.valueOf(input.getOrDefault("expectedProtocol", "")));
        value.put("expectedBridgeId", String.valueOf(input.getOrDefault("expectedBridgeId", "")));
        value.put("expectedCapabilityRevision", longValue(input.get("expectedCapabilityRevision"), 0));
        value.put("blockingCompatibility", bool(input.get("blockingCompatibility"), false));
        value.put("humanApprovalRequired", bool(input.get("humanApprovalRequired"), false));
        value.put("humanApproved", bool(input.get("humanApproved"), false));
        value.put("unresolvedDrainFailure", bool(input.get("unresolvedDrainFailure"), false));
        value.put("promotionState", "REGISTERED");
        value.put("rollbackState", "AVAILABLE");
        return view(database.put(scope, "deployment", deploymentId, revision, value));
    }

    public Map<String, Object> deploymentAction(
            PlatformDatabase.Scope scope, String deploymentId, String requestedAction, long revision
    ) {
        String action = requestedAction == null ? "" : requestedAction.toUpperCase(Locale.ROOT);
        if (!DEPLOYMENT_ACTIONS.contains(action)) throw new IllegalArgumentException("unsupported deployment action");
        PlatformDatabase.StoredRecord existing = database.get(scope, "deployment", id(deploymentId))
                .orElseThrow(() -> new IllegalArgumentException("unknown deployment"));
        if (existing.revision() != revision) throw new PlatformDatabase.RevisionConflict(revision, existing.revision());
        Map<String, Object> next = new LinkedHashMap<>(existing.value());
        String blue = String.valueOf(next.get("blueBackend"));
        String green = String.valueOf(next.get("greenBackend"));
        switch (action) {
            case "REGISTER_GREEN" -> next.put("promotionState", "GREEN_REGISTERED");
            case "VALIDATE_GREEN" -> {
                requirePromotionGates(next, green, false);
                next.put("promotionState", "GREEN_VALIDATED");
                events.publish(OniEvent.of(OniEventType.PUSH_NOTIFICATION_REQUESTED,
                        scope.tenantId(), scope.proxyId(), Map.of(
                                "topic", "GREEN_READY_FOR_PROMOTION", "deploymentId", existing.id())));
            }
            case "START_CANARY" -> {
                requirePromotionGates(next, green, false);
                next.put("promotionState", "CANARY_ACTIVE");
                RoutingOverrides.canaryEnabled(scope, true);
            }
            case "STOP_CANARY" -> {
                next.put("promotionState", "CANARY_STOPPED");
                RoutingOverrides.canaryEnabled(scope, false);
            }
            case "PROMOTE_GREEN" -> {
                requirePromotionGates(next, green, true);
                next.put("activeColor", "GREEN");
                next.put("promotionState", "PROMOTED");
                RoutingOverrides.promoted(scope, green);
                events.publish(OniEvent.of(OniEventType.BACKEND_PROMOTED,
                        scope.tenantId(), scope.proxyId(), Map.of("deploymentId", existing.id())));
            }
            case "DRAIN_BLUE" -> {
                long registryRevision = longValue(proxy.backendRegistry().get("revision"), -1);
                proxy.setBackendDraining(blue, true, registryRevision);
                next.put("promotionState", "BLUE_DRAINING");
            }
            case "ROLLBACK_TO_BLUE" -> {
                setAvailable(blue);
                requirePromotionGates(next, blue, false);
                next.put("activeColor", "BLUE");
                next.put("rollbackState", "ROLLED_BACK");
                RoutingOverrides.promoted(scope, blue);
                events.publish(OniEvent.of(OniEventType.BACKEND_ROLLED_BACK,
                        scope.tenantId(), scope.proxyId(), Map.of("deploymentId", existing.id())));
            }
            case "RETIRE_BLUE" -> {
                if (!"GREEN".equals(next.get("activeColor"))) {
                    throw new IllegalStateException("blue can be retired only after green promotion");
                }
                long registryRevision = longValue(proxy.backendRegistry().get("revision"), -1);
                proxy.setBackendEnabled(blue, false, registryRevision);
                next.put("promotionState", "BLUE_RETIRED");
                next.put("rollbackState", "UNAVAILABLE");
            }
            default -> throw new IllegalArgumentException("unsupported deployment action");
        }
        next.put("lastAction", action);
        next.put("updatedAt", Instant.now().toString());
        return view(database.put(scope, "deployment", existing.id(), revision, next));
    }

    private void requirePromotionGates(Map<String, Object> deployment, String backendName, boolean promotion) {
        Map<String, Object> backend = proxy.backends().stream()
                .filter(item -> backendName.equalsIgnoreCase(String.valueOf(item.get("name"))))
                .findFirst().orElseThrow(() -> new IllegalStateException("deployment backend is not registered"));
        Object health = backend.get("health");
        if (!(health instanceof Map<?, ?> map) || !"online".equalsIgnoreCase(String.valueOf(map.get("status")))) {
            throw new IllegalStateException("deployment backend is not healthy");
        }
        if (Boolean.TRUE.equals(backend.get("draining"))) {
            throw new IllegalStateException("deployment backend is draining");
        }
        if (bool(deployment.get("blockingCompatibility"), false)) {
            throw new IllegalStateException("deployment has a blocking compatibility result");
        }
        if (bool(deployment.get("unresolvedDrainFailure"), false)) {
            throw new IllegalStateException("deployment has an unresolved drain failure");
        }
        boolean candidate = backendName.equalsIgnoreCase(String.valueOf(deployment.get("greenBackend")));
        if (candidate) {
            String expectedProtocol = String.valueOf(deployment.getOrDefault("expectedProtocol", "")).trim();
            Map<?, ?> healthMap = health instanceof Map<?, ?> healthValues ? healthValues : Map.of();
            String actualProtocol = String.valueOf(healthMap.containsKey("advertisedProtocol")
                    ? healthMap.get("advertisedProtocol") : "");
            String actualVersion = String.valueOf(healthMap.containsKey("advertisedVersion")
                    ? healthMap.get("advertisedVersion") : "");
            if (!expectedProtocol.isBlank()
                    && !expectedProtocol.equals(actualProtocol) && !expectedProtocol.equals(actualVersion)) {
                throw new IllegalStateException("deployment backend protocol does not match the expected protocol");
            }
            String expectedBridge = String.valueOf(deployment.getOrDefault("expectedBridgeId", "")).trim();
            long expectedCapabilities = longValue(deployment.get("expectedCapabilityRevision"), 0);
            if (!expectedBridge.isBlank() || expectedCapabilities > 0) {
                Map<String, Object> bridge = bridgeStatus(backendName);
                if (!Boolean.TRUE.equals(bridge.get("connected"))) {
                    throw new IllegalStateException("deployment OniControl bridge is not connected");
                }
                if (!expectedBridge.isBlank()
                        && !expectedBridge.equals(String.valueOf(bridge.get("bridgeId")))) {
                    throw new IllegalStateException("deployment OniControl bridge identity does not match");
                }
                if (expectedCapabilities > 0
                        && expectedCapabilities != longValue(bridge.get("capabilityRevision"), -1)) {
                    throw new IllegalStateException("deployment capability revision does not match");
                }
            }
        }
        if (promotion && bool(deployment.get("humanApprovalRequired"), false)
                && !bool(deployment.get("humanApproved"), false)) {
            throw new IllegalStateException("deployment requires human approval before promotion");
        }
    }

    private void setAvailable(String backend) {
        Map<String, Object> entry = proxy.backends().stream()
                .filter(item -> backend.equalsIgnoreCase(String.valueOf(item.get("name"))))
                .findFirst().orElseThrow(() -> new IllegalStateException("deployment backend is not registered"));
        if (!Boolean.TRUE.equals(entry.get("enabled"))) {
            proxy.setBackendEnabled(backend, true, longValue(proxy.backendRegistry().get("revision"), -1));
        }
        if (Boolean.TRUE.equals(entry.get("draining"))) {
            proxy.setBackendDraining(backend, false, longValue(proxy.backendRegistry().get("revision"), -1));
        }
    }

    private static boolean expired(String value) {
        return value != null && !value.isBlank() && !Instant.parse(value).isAfter(Instant.now());
    }

    private Map<String, Object> bridgeStatus(String backend) {
        Object raw = proxy.controlStatus().get("bridges");
        if (!(raw instanceof List<?> bridges)) return Map.of();
        for (Object item : bridges) {
            if (item instanceof Map<?, ?> bridge
                    && backend.equalsIgnoreCase(String.valueOf(bridge.get("backend")))) {
                Map<String, Object> result = new LinkedHashMap<>();
                bridge.forEach((key, value) -> result.put(String.valueOf(key), value));
                return Map.copyOf(result);
            }
        }
        return Map.of();
    }

    private void saveHistory(
            PlatformDatabase.Scope scope, PlatformDatabase.StoredRecord existing, String operation
    ) {
        database.put(scope, "dynamic-backend-history", UUID.randomUUID().toString(), null, Map.of(
                "backend", existing.id(), "operation", operation,
                "definition", existing.value(), "savedAt", Instant.now().toString()));
    }

    private static List<Map<String, Object>> redactedDefinitions(List<PlatformDatabase.StoredRecord> records) {
        return records.stream().map(record -> {
            Map<String, Object> item = new LinkedHashMap<>(view(record));
            if (item.remove("secretReference") != null) item.put("secretConfigured", true);
            Object definition = item.get("definition");
            if (definition instanceof Map<?, ?> map) {
                Map<String, Object> redacted = new LinkedHashMap<>();
                map.forEach((key, value) -> redacted.put(String.valueOf(key), value));
                if (redacted.remove("secretReference") != null) redacted.put("secretConfigured", true);
                item.put("definition", Map.copyOf(redacted));
            }
            return Map.copyOf(item);
        }).toList();
    }

    private static Map<String, Object> redactRecord(Map<String, Object> record) {
        Map<String, Object> result = new LinkedHashMap<>(record);
        if (result.remove("secretReference") != null) result.put("secretConfigured", true);
        return Map.copyOf(result);
    }

    private static Map<String, String> definitionValues(Map<?, ?> definition, long runtimeRevision) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String key : List.of("name", "host", "port", "protocol", "proxyId", "bridgeId", "keyId")) {
            values.put(key, String.valueOf(definition.get(key)));
        }
        String secret = String.valueOf(
                definition.containsKey("secretReference") ? definition.get("secretReference") : "");
        if (secret.startsWith("env:")) values.put("secretEnvironment", secret.substring(4));
        else if (secret.startsWith("file:")) values.put("secretFile", secret.substring(5));
        else throw new IllegalStateException("registry history secret reference is invalid");
        values.put("revision", Long.toString(runtimeRevision));
        return values;
    }

    private static String requiredString(Map<String, String> values, String field) {
        String value = values.get(field);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }

    private static String secretReference(Map<String, String> values) {
        String environment = values.getOrDefault("secretEnvironment", "").trim();
        String file = values.getOrDefault("secretFile", "").trim();
        if (environment.isBlank() == file.isBlank()) {
            throw new IllegalArgumentException("exactly one secret reference is required");
        }
        return environment.isBlank() ? "file:" + file : "env:" + environment;
    }

    private static String bounded(String value, int maximum) {
        String result = value == null ? "" : value.trim();
        return result.length() <= maximum ? result : result.substring(0, maximum);
    }
}
