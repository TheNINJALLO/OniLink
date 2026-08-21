package dev.onistone.onilink.platform.actions;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;

/** Closed typed action registry shared by workflows and authenticated APIs. */
public final class ActionRegistry {
    public enum TenantBehavior { EXACT_SCOPE, OWNER_ONLY }

    public record Context(String actor, String role, String tenantId, String proxyId, String correlationId) {
        public Context {
            actor = required(actor, "actor");
            role = required(role, "role").toLowerCase(Locale.ROOT);
            tenantId = normalizedScope(tenantId);
            proxyId = normalizedScope(proxyId);
            correlationId = correlationId == null || correlationId.isBlank()
                    ? UUID.randomUUID().toString() : correlationId;
        }
    }

    public record Descriptor(
            String id,
            int version,
            String requiredRole,
            TenantBehavior tenantBehavior,
            Set<String> requiredFields,
            Set<String> optionalFields,
            Duration timeout,
            boolean idempotent,
            boolean confirmationRequired,
            boolean safeDuringTransfer,
            String auditSummary,
            BiFunction<Context, Map<String, Object>, Map<String, Object>> handler
    ) {
        public Descriptor {
            if (id == null || !id.matches("[A-Z][A-Z0-9_]{2,63}")) {
                throw new IllegalArgumentException("action ID is invalid");
            }
            if (version < 1) throw new IllegalArgumentException("action version must be positive");
            requiredRole = required(requiredRole, "requiredRole").toLowerCase(Locale.ROOT);
            tenantBehavior = tenantBehavior == null ? TenantBehavior.EXACT_SCOPE : tenantBehavior;
            requiredFields = Set.copyOf(requiredFields == null ? Set.of() : requiredFields);
            optionalFields = Set.copyOf(optionalFields == null ? Set.of() : optionalFields);
            timeout = timeout == null ? Duration.ofSeconds(10) : timeout;
            auditSummary = required(auditSummary, "auditSummary");
            if (handler == null) throw new IllegalArgumentException("handler is required");
        }

        public Map<String, Object> publicView() {
            return Map.ofEntries(
                    Map.entry("id", id), Map.entry("version", version),
                    Map.entry("requiredRole", requiredRole), Map.entry("tenantBehavior", tenantBehavior.name()),
                    Map.entry("requiredFields", requiredFields), Map.entry("optionalFields", optionalFields),
                    Map.entry("timeoutMillis", timeout.toMillis()), Map.entry("idempotent", idempotent),
                    Map.entry("confirmationRequired", confirmationRequired),
                    Map.entry("safeDuringTransfer", safeDuringTransfer), Map.entry("auditSummary", auditSummary));
        }
    }

    public record Result(String action, String correlationId, Instant completedAt, Map<String, Object> value) {}

    private static final int MAX_IDEMPOTENT_RESULTS = 2_048;
    private final Map<String, Descriptor> actions = new LinkedHashMap<>();
    private final LinkedHashMap<String, CachedResult> results = new LinkedHashMap<>();

    public synchronized void register(Descriptor descriptor) {
        if (actions.putIfAbsent(descriptor.id(), descriptor) != null) {
            throw new IllegalArgumentException("duplicate action " + descriptor.id());
        }
    }

    public Result execute(String id, Context context, Map<String, Object> input, String idempotencyKey) {
        Descriptor descriptor;
        synchronized (this) {
            descriptor = actions.get(id);
            if (descriptor == null) throw new IllegalArgumentException("unknown action " + id);
            if (descriptor.idempotent() && idempotencyKey != null && !idempotencyKey.isBlank()) {
                String cacheKey = cacheKey(context, id, idempotencyKey);
                CachedResult prior = results.get(cacheKey);
                if (prior != null) {
                    if (prior.inputHash() != (input == null ? Map.of() : input).hashCode()) {
                        throw new IllegalArgumentException("idempotency key was reused with different action input");
                    }
                    return prior.result();
                }
            }
        }
        authorize(descriptor, context);
        Map<String, Object> supplied = input == null ? Map.of() : input;
        if (descriptor.confirmationRequired() && !Boolean.TRUE.equals(supplied.get("_confirmed"))) {
            throw new SecurityException("action requires explicit confirmation");
        }
        Map<String, Object> validated = validate(descriptor, supplied);
        Map<String, Object> output = Map.copyOf(descriptor.handler().apply(context, validated));
        Result result = new Result(id, context.correlationId(), Instant.now(), output);
        if (descriptor.idempotent() && idempotencyKey != null && !idempotencyKey.isBlank()) {
            synchronized (this) {
                results.put(cacheKey(context, id, idempotencyKey), new CachedResult(supplied.hashCode(), result));
                while (results.size() > MAX_IDEMPOTENT_RESULTS) {
                    results.remove(results.keySet().iterator().next());
                }
            }
        }
        return result;
    }

    public synchronized List<Map<String, Object>> descriptors() {
        return actions.values().stream().map(Descriptor::publicView).toList();
    }

    private static Map<String, Object> validate(Descriptor descriptor, Map<String, Object> input) {
        Map<String, Object> mutable = new LinkedHashMap<>(input == null ? Map.of() : input);
        mutable.remove("_confirmed");
        Map<String, Object> values = Map.copyOf(mutable);
        Set<String> allowed = new java.util.LinkedHashSet<>(descriptor.requiredFields());
        allowed.addAll(descriptor.optionalFields());
        List<String> unknown = values.keySet().stream().filter(key -> !allowed.contains(key)).toList();
        if (!unknown.isEmpty()) throw new IllegalArgumentException("unknown action fields: " + unknown);
        List<String> missing = descriptor.requiredFields().stream()
                .filter(key -> values.get(key) == null || String.valueOf(values.get(key)).isBlank()).toList();
        if (!missing.isEmpty()) throw new IllegalArgumentException("missing action fields: " + missing);
        return values;
    }

    private static void authorize(Descriptor descriptor, Context context) {
        if (rank(context.role()) < rank(descriptor.requiredRole())) {
            throw new SecurityException("action requires " + descriptor.requiredRole());
        }
        if (descriptor.tenantBehavior() == TenantBehavior.OWNER_ONLY && !"owner".equals(context.role())) {
            throw new SecurityException("action is owner-only");
        }
    }

    private static int rank(String role) {
        return switch (role) {
            case "owner" -> 4;
            case "admin" -> 3;
            case "operator" -> 2;
            case "viewer", "tenant" -> 1;
            default -> 0;
        };
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }

    private static String normalizedScope(String value) {
        return value == null || value.isBlank() ? "default" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String cacheKey(Context context, String id, String idempotencyKey) {
        return context.tenantId() + '\0' + context.proxyId() + '\0' + id + '\0' + idempotencyKey;
    }

    private record CachedResult(int inputHash, Result result) {}
}
