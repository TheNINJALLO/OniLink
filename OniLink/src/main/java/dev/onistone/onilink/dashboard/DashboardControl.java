package dev.onistone.onilink.dashboard;

import java.util.List;
import java.util.Map;

/** Runtime boundary used by the HTTP layer and by isolated dashboard integration tests. */
interface DashboardControl extends AutoCloseable {
    Map<String, Object> state();

    List<Map<String, Object>> players(boolean includeAddresses);

    List<Map<String, Object>> backends(boolean includeAddresses);

    default Map<String, Object> packetMonitor(Map<String, String> filters) {
        return Map.of(
                "enabled", false,
                "privacy", "Packet monitoring is unavailable for this runtime.",
                "summary", Map.of(),
                "protocols", List.of(),
                "selectedPair", Map.of(),
                "routeAvailable", false,
                "records", List.of(),
                "matches", List.of(),
                "catalog", List.of(),
                "catalogCount", 0
        );
    }

    default Map<String, Object> allowlist() {
        return Map.of("enabled", false, "count", 0, "entries", List.of());
    }

    default Map<String, Object> oniControlStatus() {
        return Map.of("controlEnabled", false, "available", false,
                "message", "OniControl is unavailable for this runtime");
    }

    default Map<String, Object> oniControlCapabilities(Map<String, String> values) {
        return Map.of("target", Map.of(), "actions", List.of());
    }

    default Map<String, Object> protocolLabStatus(String actor) {
        return Map.of("enabled", false, "sessionActive", false, "models", List.of());
    }

    default Map<String, Object> protocolLabSession(String actor, boolean start) {
        throw new IllegalStateException("Protocol Lab is unavailable for this runtime");
    }

    default Map<String, Object> protocolLab(String actor, Map<String, String> values, boolean send) {
        throw new IllegalStateException("Protocol Lab is unavailable for this runtime");
    }

    default Map<String, Object> oniControlPreview(
            String actor, String role, Map<String, String> values) {
        throw new IllegalStateException("OniControl is unavailable for this runtime");
    }

    default Map<String, Object> oniControlExecute(
            String actor, String role, String token, boolean confirmed) {
        throw new IllegalStateException("OniControl is unavailable for this runtime");
    }

    default Map<String, Object> oniControlPlanValidate(
            String actor, String role, Map<String, String> values) {
        throw new IllegalStateException("OniControl plans are unavailable for this runtime");
    }

    default Map<String, Object> oniControlPlanPreview(
            String actor, String role, Map<String, String> values) {
        throw new IllegalStateException("OniControl plans are unavailable for this runtime");
    }

    default Map<String, Object> oniControlPlanExecute(
            String actor, String role, String token, boolean confirmed) {
        throw new IllegalStateException("OniControl plans are unavailable for this runtime");
    }

    default Map<String, Object> oniControlHistory() {
        return Map.of("history", List.of());
    }

    default Map<String, Object> oniPacketRules() {
        return Map.of("version", 1, "rules", List.of());
    }

    default ActionResult replaceOniPacketRules(String json) {
        return new ActionResult(false, "OniPacket rules are unavailable for this runtime");
    }

    default Map<String, Object> backendRegistry() {
        return Map.of("revision", 0, "backends", backends(false));
    }

    default Map<String, Object> registerBackend(Map<String, String> values) {
        throw new IllegalStateException("Dynamic backend registration is unavailable for this runtime");
    }

    default Map<String, Object> updateBackend(Map<String, String> values) {
        throw new IllegalStateException("Dynamic backend updates are unavailable for this runtime");
    }

    default Map<String, Object> removeBackend(Map<String, String> values) {
        throw new IllegalStateException("Dynamic backend removal is unavailable for this runtime");
    }

    default Map<String, Object> setBackendDraining(String backend, boolean draining, long revision) {
        throw new IllegalStateException("Backend draining is unavailable for this runtime");
    }

    default Map<String, Object> setBackendEnabled(String backend, boolean enabled, long revision) {
        throw new IllegalStateException("Backend availability changes are unavailable for this runtime");
    }

    default ActionResult allowlistAdd(String xuid, String name) {
        return new ActionResult(false, "Allowlist management is unavailable");
    }

    default ActionResult allowlistRemove(String xuid) {
        return new ActionResult(false, "Allowlist management is unavailable");
    }

    ActionResult transfer(String player, String backend);

    default ActionResult messagePlayer(String xuid, String message) {
        return new ActionResult(false, "Player messaging is unavailable");
    }

    default ActionResult traceXuid(String xuid, long milliseconds) {
        return new ActionResult(false, "Journey trace control is unavailable");
    }

    ActionResult disconnect(String player, String reason);

    ActionResult alert(String message);

    ActionResult trace(String player, long milliseconds);

    void shutdown();

    @Override
    default void close() {
    }

    record ActionResult(boolean success, String message) {
        Map<String, Object> asMap() {
            return Map.of("success", success, "message", message);
        }
    }
}
