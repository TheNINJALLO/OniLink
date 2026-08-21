package dev.onistone.onilink.packet;

import dev.onistone.onilink.control.ActionType;
import dev.onistone.onilink.control.ControlJson;
import dev.onistone.onilink.control.ValidatedActionPayload;
import dev.onistone.onilink.control.PacketOrigin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Tenant-scoped rule persistence using bounded JSON, same-directory temp files, and backups. */
public final class PacketRuleStore {
    private static final int MAX_DOCUMENT_CHARACTERS = 4 * 1024 * 1024;
    private final Path root;
    private final int maximumRules;

    public PacketRuleStore(Path dataDirectory, int maximumRules) {
        if (dataDirectory == null || maximumRules < 1) throw new IllegalArgumentException("rule data directory and limit are required");
        this.root = dataDirectory.toAbsolutePath().normalize().resolve("packet-rules");
        this.maximumRules = maximumRules;
    }

    public synchronized List<PacketRule> load(String tenantId, String proxyId) throws IOException {
        Scope scope = Scope.of(tenantId, proxyId);
        Path file = scopeFile(scope);
        if (!Files.exists(file)) return List.of();
        if (!Files.isRegularFile(file) || Files.isSymbolicLink(file)) {
            throw new IOException("packet rule document is not a regular file");
        }
        String json = Files.readString(file, StandardCharsets.UTF_8);
        return parseDocument(json, scope);
    }

    public List<PacketRule> parseDocument(String json, String tenantId, String proxyId) {
        return parseDocument(json, Scope.of(tenantId, proxyId));
    }

    private List<PacketRule> parseDocument(String json, Scope scope) {
        Map<String, Object> document = ControlJson.parseObject(json, MAX_DOCUMENT_CHARACTERS);
        exact(document, Set.of("version", "rules"), "rule document");
        if (integer(document.get("version"), "version") != 1) {
            throw new IllegalArgumentException("unsupported packet rule document version");
        }
        List<Object> values = list(document.get("rules"), "rules", 0, maximumRules);
        List<PacketRule> rules = new ArrayList<>();
        for (Object value : values) rules.add(decodeRule(object(value, "rule"), scope));
        return List.copyOf(rules);
    }

    public String encodeDocument(List<PacketRule> rules, String tenantId, String proxyId) {
        Scope scope = Scope.of(tenantId, proxyId);
        List<PacketRule> safe = List.copyOf(rules == null ? List.of() : rules);
        if (safe.size() > maximumRules) throw new IllegalArgumentException("packet rule count exceeds " + maximumRules);
        for (PacketRule rule : safe) {
            if (!rule.tenantId().equals(scope.tenantId) || !rule.proxyId().equals(scope.proxyId)) {
                throw new IllegalArgumentException("cannot export a packet rule outside the requested tenant/proxy scope");
            }
        }
        return ControlJson.encode(Map.of("version", 1, "rules", safe.stream().map(PacketRuleStore::encodeRule).toList()));
    }

    public synchronized void save(String tenantId, String proxyId, List<PacketRule> rules) throws IOException {
        Scope scope = Scope.of(tenantId, proxyId);
        List<PacketRule> safe = List.copyOf(rules == null ? List.of() : rules);
        if (safe.size() > maximumRules) throw new IllegalArgumentException("packet rule count exceeds " + maximumRules);
        for (PacketRule rule : safe) {
            if (!rule.tenantId().equals(scope.tenantId) || !rule.proxyId().equals(scope.proxyId)) {
                throw new IllegalArgumentException("cannot write a packet rule outside the requested tenant/proxy scope");
            }
        }
        byte[] bytes = encodeDocument(safe, tenantId, proxyId).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_DOCUMENT_CHARACTERS) throw new IllegalArgumentException("packet rule document is too large");

        Path file = scopeFile(scope);
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
        try {
            Files.write(temporary, bytes);
            if (Files.exists(file)) writeBackup(file);
            atomicMove(temporary, file);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void writeBackup(Path file) throws IOException {
        Path backup = file.resolveSibling(file.getFileName() + ".bak");
        Path temporary = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".bak.tmp");
        try {
            Files.copy(file, temporary, StandardCopyOption.REPLACE_EXISTING);
            atomicMove(temporary, backup);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void atomicMove(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Path scopeFile(Scope scope) {
        Path file = root.resolve(scope.tenantId).resolve(scope.proxyId + ".json").normalize();
        if (!file.startsWith(root)) throw new IllegalArgumentException("packet rule scope escapes the data directory");
        return file;
    }

    private static Map<String, Object> encodeRule(PacketRule rule) {
        PacketRuleCondition condition = rule.condition();
        Map<String, Object> conditionJson = new LinkedHashMap<>();
        conditionJson.put("packetTypes", condition.packetTypes().stream().sorted().toList());
        conditionJson.put("backends", condition.backends().stream().sorted().toList());
        conditionJson.put("clientProtocols", condition.clientProtocols().stream().sorted().toList());
        conditionJson.put("backendProtocols", condition.backendProtocols().stream().sorted().toList());
        conditionJson.put("dimensions", condition.dimensions().stream().sorted().toList());
        conditionJson.put("sessionPhases", condition.sessionPhases().stream().sorted().toList());
        conditionJson.put("origins", condition.origins().stream().map(Enum::name).sorted().toList());
        conditionJson.put("xuids", condition.xuids().stream().sorted().toList());
        conditionJson.put("connectionIds", condition.connectionIds().stream().sorted().toList());
        conditionJson.put("joinedWorld", condition.joinedWorld());
        conditionJson.put("transferring", condition.transferring());
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("type", rule.action().type().name());
        if (rule.action().semanticAction() != null) {
            action.put("semanticAction", rule.action().semanticAction().name());
            action.put("payloadVersion", rule.action().payload().version());
            action.put("payload", rule.action().payload().values());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", rule.id());
        result.put("tenantId", rule.tenantId());
        result.put("proxyId", rule.proxyId());
        result.put("enabled", rule.enabled());
        result.put("priority", rule.priority());
        result.put("direction", rule.direction().name());
        result.put("stage", rule.stage().name());
        result.put("condition", conditionJson);
        result.put("action", action);
        result.put("description", rule.description());
        return Map.copyOf(result);
    }

    private static PacketRule decodeRule(Map<String, Object> json, Scope scope) {
        exact(json, Set.of("id", "tenantId", "proxyId", "enabled", "priority", "direction", "stage",
                "condition", "action", "description"), "rule");
        String tenantId = string(json.get("tenantId"), "tenantId", 64);
        String proxyId = string(json.get("proxyId"), "proxyId", 64);
        if (!tenantId.equalsIgnoreCase(scope.tenantId) || !proxyId.equalsIgnoreCase(scope.proxyId)) {
            throw new IllegalArgumentException("stored rule scope does not match its file");
        }
        Map<String, Object> conditionJson = object(json.get("condition"), "condition");
        exact(conditionJson, Set.of("packetTypes", "backends", "clientProtocols", "backendProtocols",
                "dimensions", "sessionPhases", "origins", "xuids", "connectionIds", "joinedWorld", "transferring"), "condition");
        PacketRuleCondition condition = new PacketRuleCondition(
                strings(conditionJson.get("packetTypes"), "packetTypes"),
                strings(conditionJson.get("backends"), "backends"),
                integers(conditionJson.get("clientProtocols"), "clientProtocols"),
                integers(conditionJson.get("backendProtocols"), "backendProtocols"),
                integers(conditionJson.get("dimensions"), "dimensions"),
                strings(conditionJson.get("sessionPhases"), "sessionPhases"),
                enumerations(PacketOrigin.class, conditionJson.get("origins"), "origins"),
                strings(conditionJson.get("xuids"), "xuids"),
                strings(conditionJson.get("connectionIds"), "connectionIds"),
                nullableBoolean(conditionJson.get("joinedWorld"), "joinedWorld"),
                nullableBoolean(conditionJson.get("transferring"), "transferring"));
        Map<String, Object> actionJson = object(json.get("action"), "action");
        exact(actionJson, Set.of("type", "semanticAction", "payloadVersion", "payload"), "action");
        PacketDecisionType type = enumeration(PacketDecisionType.class, actionJson.get("type"), "action type");
        ActionType semantic = actionJson.get("semanticAction") == null ? null
                : enumeration(ActionType.class, actionJson.get("semanticAction"), "semantic action");
        ValidatedActionPayload payload = semantic == null ? null : new ValidatedActionPayload(
                integer(actionJson.get("payloadVersion"), "payloadVersion"),
                object(actionJson.get("payload"), "payload"));
        return new PacketRule(
                string(json.get("id"), "id", 64), tenantId, proxyId,
                bool(json.get("enabled"), "enabled"), integer(json.get("priority"), "priority"),
                enumeration(PacketRuleDirection.class, json.get("direction"), "direction"),
                enumeration(PacketRuleStage.class, json.get("stage"), "stage"),
                condition, new PacketRuleAction(type, semantic, payload),
                optionalString(json.get("description"), "description", 512));
    }

    private static void exact(Map<String, Object> map, Set<String> allowed, String label) {
        for (String key : map.keySet()) if (!allowed.contains(key)) throw new IllegalArgumentException(label + " contains unknown field " + key);
    }

    private static Map<String, Object> object(Object value, String label) {
        if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException(label + " must be an object");
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) throw new IllegalArgumentException(label + " key must be text");
            result.put(key, entry.getValue());
        }
        return result;
    }

    private static List<Object> list(Object value, String label, int minimum, int maximum) {
        if (!(value instanceof List<?> list) || list.size() < minimum || list.size() > maximum) {
            throw new IllegalArgumentException(label + " must contain " + minimum + ".." + maximum + " values");
        }
        return new ArrayList<>(list);
    }

    private static Set<String> strings(Object value, String label) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (Object item : list(value, label, 0, 10_000)) result.add(string(item, label + " item", 128));
        return Set.copyOf(result);
    }

    private static Set<Integer> integers(Object value, String label) {
        LinkedHashSet<Integer> result = new LinkedHashSet<>();
        for (Object item : list(value, label, 0, 10_000)) result.add(integer(item, label + " item"));
        return Set.copyOf(result);
    }

    private static <T extends Enum<T>> Set<T> enumerations(
            Class<T> type, Object value, String label) {
        if (value == null) return Set.of();
        LinkedHashSet<T> result = new LinkedHashSet<>();
        for (Object item : list(value, label, 0, 64)) {
            result.add(enumeration(type, item, label + " item"));
        }
        return Set.copyOf(result);
    }

    private static String string(Object value, String label, int maximum) {
        if (!(value instanceof String string) || string.isBlank() || string.length() > maximum) {
            throw new IllegalArgumentException(label + " must be 1.." + maximum + " characters");
        }
        return string.trim();
    }

    private static String optionalString(Object value, String label, int maximum) {
        if (value == null) return "";
        if (!(value instanceof String string) || string.length() > maximum) throw new IllegalArgumentException(label + " is too long");
        return string.trim();
    }

    private static int integer(Object value, String label) {
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())
                || number.doubleValue() != number.intValue()) throw new IllegalArgumentException(label + " must be an integer");
        return number.intValue();
    }

    private static boolean bool(Object value, String label) {
        if (!(value instanceof Boolean bool)) throw new IllegalArgumentException(label + " must be boolean");
        return bool;
    }

    private static Boolean nullableBoolean(Object value, String label) {
        return value == null ? null : bool(value, label);
    }

    private static <T extends Enum<T>> T enumeration(Class<T> type, Object value, String label) {
        String clean = string(value, label, 64).toUpperCase(Locale.ROOT);
        try { return Enum.valueOf(type, clean); }
        catch (IllegalArgumentException exception) { throw new IllegalArgumentException("unknown " + label + " " + clean); }
    }

    private record Scope(String tenantId, String proxyId) {
        private static Scope of(String tenantId, String proxyId) {
            return new Scope(safe(tenantId, "tenant ID"), safe(proxyId, "proxy ID"));
        }

        private static String safe(String value, String label) {
            String clean = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
            if (!clean.matches("[a-z0-9][a-z0-9._-]{0,63}")) throw new IllegalArgumentException(label + " is invalid");
            return clean;
        }
    }
}
