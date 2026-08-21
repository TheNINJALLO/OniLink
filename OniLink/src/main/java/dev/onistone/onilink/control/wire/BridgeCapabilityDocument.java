package dev.onistone.onilink.control.wire;

import dev.onistone.onilink.control.ActionType;
import dev.onistone.onilink.control.ControlJson;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record BridgeCapabilityDocument(
        int controlProtocolVersion,
        String oniBridgeVersion,
        String backend,
        String bridgeId,
        String bdsVersion,
        String endstoneVersion,
        String operatingSystem,
        String executableHash,
        String activeProfile,
        String profileReviewStatus,
        Map<ActionType, Integer> supportedActions,
        int maximumRequestSize,
        int maximumBlockRegionSize,
        int maximumInventoryStackCount,
        boolean tlsActive,
        long revision
) {
    public BridgeCapabilityDocument {
        if (controlProtocolVersion != 1 || revision < 1 || maximumRequestSize < 1
                || maximumBlockRegionSize < 1 || maximumInventoryStackCount < 1) {
            throw new IllegalArgumentException("invalid bridge capability limits or revision");
        }
        oniBridgeVersion = required(oniBridgeVersion, "OniBridge version");
        backend = required(backend, "capability backend");
        bridgeId = required(bridgeId, "capability bridge ID");
        bdsVersion = required(bdsVersion, "BDS version");
        endstoneVersion = required(endstoneVersion, "Endstone version");
        operatingSystem = required(operatingSystem, "operating system");
        executableHash = required(executableHash, "executable hash");
        activeProfile = required(activeProfile, "active profile");
        profileReviewStatus = required(profileReviewStatus, "profile review status");
        supportedActions = Map.copyOf(supportedActions == null ? Map.of() : supportedActions);
    }

    public boolean supports(ActionType action, int payloadVersion) {
        return supportedActions.getOrDefault(action, 0) >= payloadVersion;
    }

    public Map<String, Object> asMap() {
        List<Map<String, Object>> actions = supportedActions.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> Map.<String, Object>of("action", entry.getKey().name(), "payloadVersion", entry.getValue()))
                .toList();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("controlProtocolVersion", controlProtocolVersion);
        values.put("oniBridgeVersion", oniBridgeVersion);
        values.put("backend", backend);
        values.put("bridgeId", bridgeId);
        values.put("bdsVersion", bdsVersion);
        values.put("endstoneVersion", endstoneVersion);
        values.put("operatingSystem", operatingSystem);
        values.put("executableHash", executableHash);
        values.put("activeProfile", activeProfile);
        values.put("profileReviewStatus", profileReviewStatus);
        values.put("supportedActions", actions);
        values.put("maximumRequestSize", maximumRequestSize);
        values.put("maximumBlockRegionSize", maximumBlockRegionSize);
        values.put("maximumInventoryStackCount", maximumInventoryStackCount);
        values.put("tlsActive", tlsActive);
        values.put("revision", revision);
        return values;
    }

    public static BridgeCapabilityDocument parse(Map<String, Object> values) {
        Map<ActionType, Integer> actions = new EnumMap<>(ActionType.class);
        Object rawActions = values.get("supportedActions");
        if (!(rawActions instanceof List<?> list)) throw new IllegalArgumentException("supportedActions must be an array");
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) throw new IllegalArgumentException("capability action must be an object");
            Object action = map.get("action");
            Object payloadVersion = map.get("payloadVersion");
            if (!(action instanceof String name) || !(payloadVersion instanceof Number number)) {
                throw new IllegalArgumentException("capability action requires action and payloadVersion");
            }
            ActionType type;
            try {
                type = ActionType.valueOf(name);
            } catch (IllegalArgumentException exception) {
                // A newer bridge may advertise a newer semantic action. The older proxy safely ignores it.
                continue;
            }
            int version = number.intValue();
            if (version < 1 || actions.putIfAbsent(type, version) != null) {
                throw new IllegalArgumentException("invalid or duplicate capability action " + name);
            }
        }
        return new BridgeCapabilityDocument(
                integer(values, "controlProtocolVersion"), text(values, "oniBridgeVersion"),
                text(values, "backend"), text(values, "bridgeId"), text(values, "bdsVersion"),
                text(values, "endstoneVersion"), text(values, "operatingSystem"),
                text(values, "executableHash"), text(values, "activeProfile"),
                text(values, "profileReviewStatus"), actions, integer(values, "maximumRequestSize"),
                integer(values, "maximumBlockRegionSize"), integer(values, "maximumInventoryStackCount"),
                bool(values, "tlsActive"), longValue(values, "revision"));
    }

    private static String text(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (!(value instanceof String text)) throw new IllegalArgumentException(key + " must be a string");
        return text;
    }

    private static int integer(Map<String, Object> values, String key) {
        long value = longValue(values, key);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) throw new IllegalArgumentException(key + " is out of range");
        return (int) value;
    }

    private static long longValue(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (!(value instanceof Number number) || value instanceof Double || value instanceof Float) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
        return number.longValue();
    }

    private static boolean bool(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (!(value instanceof Boolean bool)) throw new IllegalArgumentException(key + " must be a boolean");
        return bool;
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank() || value.length() > 256) throw new IllegalArgumentException(label + " is required");
        return value;
    }
}
