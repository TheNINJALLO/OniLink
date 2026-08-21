package dev.onistone.onilink.dashboard;

import dev.onistone.onilink.control.ActionAuditRecord;
import dev.onistone.onilink.control.ActionType;
import dev.onistone.onilink.control.ControlActionRequest;
import dev.onistone.onilink.control.ControlActionResult;
import dev.onistone.onilink.control.ControlActionPlan;
import dev.onistone.onilink.control.ControlActionStep;
import dev.onistone.onilink.control.ControlJson;
import dev.onistone.onilink.control.ControlRole;
import dev.onistone.onilink.control.FailurePolicy;
import dev.onistone.onilink.control.OniControlRuntime;
import dev.onistone.onilink.control.ResolvedTarget;
import dev.onistone.onilink.control.TargetSelector;
import dev.onistone.onilink.control.ValidatedActionPayload;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Authenticated dashboard boundary for typed OniControl operations. */
final class DashboardOniControl {
    private static final int MAX_PAYLOAD_CHARACTERS = 64 * 1024;
    private static final int MAX_PREVIEWS = 256;
    private static final long PREVIEW_SECONDS = 60;
    private final OniControlRuntime runtime;
    private final SecureRandom random = new SecureRandom();
    private final LinkedHashMap<String, Preview> previews = new LinkedHashMap<>();
    private final LinkedHashMap<String, PlanPreview> planPreviews = new LinkedHashMap<>();
    private final AtomicLong nextPlanRevision = new AtomicLong(1);

    DashboardOniControl(OniControlRuntime runtime) {
        this.runtime = runtime;
    }

    Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<>(runtime.status());
        result.put("available", runtime.controlEnabled());
        result.put("capabilities", runtime.capabilities());
        result.put("historyCount", runtime.auditSnapshot().size());
        return Map.copyOf(result);
    }

    Map<String, Object> capabilities(Map<String, String> values) {
        ResolvedTarget target = runtime.resolve(new TargetSelector(
                value(values.get("xuid")), value(values.get("connectionId")), runtime.tenantId(),
                runtime.proxyId(), value(values.get("backend")), value(values.get("player"))));
        return Map.of("target", targetMap(target), "actions", runtime.actionCapabilities(target));
    }

    Map<String, Object> protocolLabStatus(String actor) {
        return runtime.protocolLabStatus(actor);
    }

    Map<String, Object> protocolLabSession(String actor, boolean start) {
        return start ? runtime.protocolLabStart(actor) : runtime.protocolLabStop(actor);
    }

    Map<String, Object> protocolLab(String actor, Map<String, String> values, boolean send) {
        return runtime.protocolLabValidate(actor, values, send);
    }

    synchronized Map<String, Object> preview(String actor, String wireRole, Map<String, String> values) {
        prune();
        String safeActor = required(actor, "actor", 128);
        ControlRole role = role(wireRole);
        ActionType action = action(values.get("action"));
        if (!role.allows(action.minimumRole())) throw new IllegalArgumentException("Your role cannot execute " + action);
        TargetSelector selector = new TargetSelector(
                value(values.get("xuid")), value(values.get("connectionId")),
                runtime.tenantId(), runtime.proxyId(), value(values.get("backend")), value(values.get("player")));
        ResolvedTarget resolved = runtime.resolve(selector);
        Map<String, Object> capability = runtime.actionCapabilities(resolved).stream()
                .filter(item -> action.name().equals(item.get("action")))
                .findFirst().orElseThrow();
        if (!Boolean.TRUE.equals(capability.get("supported"))) {
            throw new IllegalArgumentException(String.valueOf(capability.get("reason")));
        }
        String payloadJson = value(values.get("payload"));
        Map<String, Object> payloadValues = ControlJson.parseObject(
                payloadJson.isBlank() ? "{}" : payloadJson, MAX_PAYLOAD_CHARACTERS);
        ValidatedActionPayload payload = new ValidatedActionPayload(1, payloadValues);
        String reason = optional(values.get("reason"), 512);
        Instant now = Instant.now();
        Instant expires = now.plusSeconds(PREVIEW_SECONDS);
        String revision = UUID.randomUUID().toString();
        byte[] tokenBytes = new byte[32];
        random.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        Preview preview = new Preview(safeActor, role, action, payload, resolved, reason, revision, expires);
        while (previews.size() >= MAX_PREVIEWS) previews.remove(previews.keySet().iterator().next());
        previews.put(digest(token), preview);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("confirmationToken", token);
        result.put("revision", revision);
        result.put("expiresAt", expires.toString());
        result.put("action", action.name());
        result.put("executionPlane", action.executionPlane().name());
        result.put("destructive", action.destructive());
        result.put("target", targetMap(resolved));
        result.put("payloadSummary", Map.of("version", 1, "fields", payload.values().keySet().stream().sorted().toList()));
        result.put("reason", reason);
        return Map.copyOf(result);
    }

    Map<String, Object> execute(String actor, String wireRole, String token, boolean confirmed) {
        Preview preview;
        synchronized (this) {
            prune();
            preview = previews.remove(digest(required(token, "confirmation token", 256)));
        }
        if (preview == null) throw new IllegalArgumentException("Confirmation token is invalid, expired, or already used");
        ControlRole role = role(wireRole);
        if (!preview.actor.equals(required(actor, "actor", 128)) || preview.role != role) {
            throw new IllegalArgumentException("Confirmation token belongs to another authenticated account or role");
        }
        if (preview.action.destructive() && !confirmed) {
            throw new IllegalArgumentException("This destructive action requires explicit confirmation");
        }
        ResolvedTarget current = runtime.resolve(new TargetSelector(
                preview.target.xuid(), preview.target.connectionId(), runtime.tenantId(), runtime.proxyId(),
                preview.target.backend(), ""));
        if (!current.equals(preview.target)) {
            throw new IllegalArgumentException("Target state changed after preview; create a new preview");
        }
        Instant now = Instant.now();
        ControlActionRequest request = new ControlActionRequest(
                UUID.randomUUID(), UUID.randomUUID().toString(),
                new TargetSelector(current.xuid(), current.connectionId(), current.tenantId(), current.proxyId(),
                        current.backend(), ""),
                preview.action, preview.action.executionPlane(), preview.payload,
                preview.actor, preview.role, current.tenantId(), current.proxyId(), now,
                now.plusSeconds(30), false, preview.reason, preview.revision, "");
        try {
            ControlActionResult result = runtime.execute(request).get(35, TimeUnit.SECONDS);
            return resultMap(result);
        } catch (java.util.concurrent.TimeoutException exception) {
            throw new IllegalStateException("OniControl action did not complete before the dashboard timeout");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("OniControl action was interrupted");
        } catch (java.util.concurrent.ExecutionException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            throw new IllegalStateException("OniControl action failed: " + safe(cause.getMessage()));
        }
    }

    Map<String, Object> validatePlan(String actor, String wireRole, Map<String, String> values) {
        ControlActionPlan plan = buildPlan(actor, wireRole, values.get("plan"));
        return planSummary(plan, false, "", Instant.EPOCH);
    }

    synchronized Map<String, Object> previewPlan(String actor, String wireRole, Map<String, String> values) {
        prune();
        ControlActionPlan plan = buildPlan(actor, wireRole, values.get("plan"));
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expires = Instant.now().plusSeconds(PREVIEW_SECONDS);
        while (planPreviews.size() >= 64) planPreviews.remove(planPreviews.keySet().iterator().next());
        planPreviews.put(digest(token), new PlanPreview(required(actor, "actor", 128), role(wireRole), plan, expires));
        return planSummary(plan, true, token, expires);
    }

    Map<String, Object> executePlan(
            String actor, String wireRole, String token, boolean confirmed) {
        PlanPreview preview;
        synchronized (this) {
            prune();
            preview = planPreviews.remove(digest(required(token, "confirmation token", 256)));
        }
        if (preview == null) throw new IllegalArgumentException(
                "Plan confirmation token is invalid, expired, or already used");
        ControlRole currentRole = role(wireRole);
        if (!preview.actor.equals(required(actor, "actor", 128)) || preview.role != currentRole) {
            throw new IllegalArgumentException("Plan confirmation token belongs to another account or role");
        }
        if (!confirmed) throw new IllegalArgumentException("Plan execution requires explicit confirmation");

        List<Map<String, Object>> results = new ArrayList<>();
        int confirmedCount = 0;
        boolean stopped = false;
        for (ControlActionStep step : preview.plan.steps()) {
            ControlActionRequest request = step.request();
            ResolvedTarget current = runtime.resolve(request.target());
            Map<String, Object> capability = runtime.actionCapabilities(current).stream()
                    .filter(item -> request.actionType().name().equals(item.get("action"))).findFirst().orElseThrow();
            if (!Boolean.TRUE.equals(capability.get("supported"))) {
                results.add(Map.of("stepId", step.stepId(), "status", "UNSUPPORTED",
                        "reason", String.valueOf(capability.get("reason"))));
                stopped = preview.plan.failurePolicy() != FailurePolicy.CONTINUE_ON_FAILURE;
                if (stopped) break;
                continue;
            }
            try {
                ControlActionResult result = runtime.execute(request).get(35, TimeUnit.SECONDS);
                Map<String, Object> item = new LinkedHashMap<>(resultMap(result));
                item.put("stepId", step.stepId());
                results.add(Map.copyOf(item));
                if (result.successful()) confirmedCount++;
                else if (preview.plan.failurePolicy() != FailurePolicy.CONTINUE_ON_FAILURE) {
                    stopped = true;
                    break;
                }
            } catch (java.util.concurrent.TimeoutException exception) {
                results.add(Map.of("stepId", step.stepId(), "status", "TIMED_OUT",
                        "reason", "Step exceeded the execution timeout"));
                stopped = preview.plan.failurePolicy() != FailurePolicy.CONTINUE_ON_FAILURE;
                if (stopped) break;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Plan execution was interrupted");
            } catch (java.util.concurrent.ExecutionException exception) {
                results.add(Map.of("stepId", step.stepId(), "status", "FAILED",
                        "reason", safe(exception.getCause() == null ? exception.getMessage()
                                : exception.getCause().getMessage())));
                stopped = preview.plan.failurePolicy() != FailurePolicy.CONTINUE_ON_FAILURE;
                if (stopped) break;
            }
        }
        String status = confirmedCount == preview.plan.steps().size() ? "CONFIRMED"
                : confirmedCount > 0 ? "PARTIAL" : "FAILED";
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("planId", preview.plan.planId());
        response.put("revision", preview.plan.revision());
        response.put("status", status);
        response.put("success", "CONFIRMED".equals(status));
        response.put("partial", "PARTIAL".equals(status));
        response.put("stopped", stopped);
        response.put("failurePolicy", preview.plan.failurePolicy().name());
        response.put("results", List.copyOf(results));
        if (preview.plan.failurePolicy() == FailurePolicy.COMPENSATE_WHEN_POSSIBLE
                && !"CONFIRMED".equals(status)) {
            response.put("compensation", "No reviewed compensation was declared; completed steps were not reversed");
        }
        return Map.copyOf(response);
    }

    private ControlActionPlan buildPlan(String actor, String wireRole, String json) {
        String safeActor = required(actor, "actor", 128);
        ControlRole actorRole = role(wireRole);
        Map<String, Object> document = ControlJson.parseObject(required(json, "plan", MAX_PAYLOAD_CHARACTERS),
                MAX_PAYLOAD_CHARACTERS);
        exactKeys(document, List.of("planId", "target", "steps", "failurePolicy", "reason",
                "evidenceReferences", "expectedResult", "confidence"), "plan");
        Map<String, Object> target = object(document.get("target"), "target");
        exactKeys(target, List.of("xuid", "connectionId", "player", "backend"), "target");
        TargetSelector selector = new TargetSelector(string(target.get("xuid")), string(target.get("connectionId")),
                runtime.tenantId(), runtime.proxyId(), string(target.get("backend")), string(target.get("player")));
        ResolvedTarget resolved = runtime.resolve(selector);
        if (!(document.get("steps") instanceof List<?> rawSteps) || rawSteps.isEmpty() || rawSteps.size() > 16) {
            throw new IllegalArgumentException("plan steps must contain 1..16 typed actions");
        }
        List<ControlActionStep> steps = new ArrayList<>();
        ControlRole requiredRole = ControlRole.VIEWER;
        Instant now = Instant.now();
        String planId = optional(string(document.get("planId")), 64);
        if (planId.isBlank()) planId = UUID.randomUUID().toString();
        for (int index = 0; index < rawSteps.size(); index++) {
            Map<String, Object> step = object(rawSteps.get(index), "step");
            exactKeys(step, List.of("stepId", "action", "payload", "reason"), "step");
            ActionType action = action(string(step.get("action")));
            if (!actorRole.allows(action.minimumRole())) throw new IllegalArgumentException(
                    "Your role cannot execute plan step " + action);
            requiredRole = requiredRole.ordinal() >= action.minimumRole().ordinal()
                    ? requiredRole : action.minimumRole();
            Map<String, Object> capability = runtime.actionCapabilities(resolved).stream()
                    .filter(item -> action.name().equals(item.get("action"))).findFirst().orElseThrow();
            if (!Boolean.TRUE.equals(capability.get("supported"))) throw new IllegalArgumentException(
                    action + " is unsupported: " + capability.get("reason"));
            Map<String, Object> payload = step.get("payload") == null ? Map.of()
                    : object(step.get("payload"), "step payload");
            String stepId = optional(string(step.get("stepId")), 64);
            if (stepId.isBlank()) stepId = "step-" + (index + 1);
            ControlActionRequest request = new ControlActionRequest(
                    UUID.randomUUID(), planId + ':' + stepId,
                    new TargetSelector(resolved.xuid(), resolved.connectionId(), resolved.tenantId(),
                            resolved.proxyId(), resolved.backend(), ""),
                    action, action.executionPlane(), new ValidatedActionPayload(1, payload), safeActor,
                    actorRole, resolved.tenantId(), resolved.proxyId(), now, now.plusSeconds(120), false,
                    optional(string(step.get("reason")), 512), Long.toString(nextPlanRevision.get()), planId);
            steps.add(new ControlActionStep(stepId, request, null));
        }
        FailurePolicy policy;
        try {
            policy = FailurePolicy.valueOf(string(document.getOrDefault("failurePolicy", "STOP_ON_FAILURE"))
                    .toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown plan failure policy");
        }
        double confidence = number(document.get("confidence"), 1, 0, 1, "confidence");
        List<String> evidence = strings(document.get("evidenceReferences"), 32, 512);
        return new ControlActionPlan(planId, steps, policy,
                optional(string(document.get("reason")), 1_024), evidence,
                optional(string(document.get("expectedResult")), 1_024), confidence, requiredRole,
                true, nextPlanRevision.getAndIncrement(), now);
    }

    private static Map<String, Object> planSummary(
            ControlActionPlan plan, boolean preview, String token, Instant expires) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("valid", true);
        result.put("planId", plan.planId());
        result.put("revision", plan.revision());
        result.put("failurePolicy", plan.failurePolicy().name());
        result.put("requiredRole", plan.requiredRole().name());
        result.put("confirmationRequired", true);
        result.put("stepCount", plan.steps().size());
        result.put("steps", plan.steps().stream().map(step -> Map.of(
                "stepId", step.stepId(), "action", step.request().actionType().name(),
                "executionPlane", step.request().executionPlane().name(),
                "target", step.request().target().xuid(), "backend", step.request().target().backend(),
                "payloadFields", step.request().payload().values().keySet().stream().sorted().toList())).toList());
        result.put("reason", plan.reason());
        result.put("expectedResult", plan.expectedResult());
        result.put("confidence", plan.confidence());
        if (preview) {
            result.put("confirmationToken", token);
            result.put("expiresAt", expires.toString());
        }
        return Map.copyOf(result);
    }

    Map<String, Object> history() {
        List<Map<String, Object>> records = new ArrayList<>();
        for (ActionAuditRecord record : runtime.auditSnapshot().reversed()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("requestId", record.requestId().toString());
            item.put("actor", record.actor());
            item.put("role", record.role().name());
            item.put("tenantId", record.tenantId());
            item.put("proxyId", record.proxyId());
            item.put("targetXuid", record.targetXuid());
            item.put("displayLabel", record.displayLabel());
            item.put("backend", record.backend());
            item.put("action", record.action().name());
            item.put("executionPlane", record.executionPlane().name());
            item.put("status", record.status().name());
            item.put("timestamp", record.timestamp().toString());
            item.put("durationMillis", record.durationMillis());
            item.put("payloadSummary", record.payloadSummary());
            item.put("resultSummary", record.resultSummary());
            item.put("failureReason", record.failureReason());
            item.put("confirmed", record.confirmed());
            records.add(Map.copyOf(item));
        }
        return Map.of("history", List.copyOf(records));
    }

    Map<String, Object> rules() {
        Map<String, Object> document = new LinkedHashMap<>(
                ControlJson.parseObject(runtime.packetRuleDocument(), 4 * 1024 * 1024));
        document.put("statistics", runtime.packetRuleStatistics());
        return Map.copyOf(document);
    }

    DashboardControl.ActionResult replaceRules(String json) {
        try {
            Map<String, Object> document = ControlJson.parseObject(json, 4 * 1024 * 1024);
            exactKeys(document, List.of("version", "rules", "statistics"), "rule document");
            if (!document.containsKey("version") || !document.containsKey("rules")) {
                throw new IllegalArgumentException("rule document requires version and rules");
            }
            if (document.containsKey("statistics") && !(document.get("statistics") instanceof List<?>)) {
                throw new IllegalArgumentException("rule statistics must be an array");
            }
            runtime.replaceRuleDocument(ControlJson.encode(Map.of(
                    "version", document.get("version"), "rules", document.get("rules"))));
            return new DashboardControl.ActionResult(true,
                    "Saved and activated " + runtime.packetRules().rules().size() + " scoped packet rule(s)");
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Could not persist packet rules: " + safe(exception.getMessage()), exception);
        }
    }

    private synchronized void prune() {
        Instant now = Instant.now();
        Iterator<Map.Entry<String, Preview>> iterator = previews.entrySet().iterator();
        while (iterator.hasNext()) if (!iterator.next().getValue().expires.isAfter(now)) iterator.remove();
        Iterator<Map.Entry<String, PlanPreview>> plans = planPreviews.entrySet().iterator();
        while (plans.hasNext()) if (!plans.next().getValue().expires.isAfter(now)) plans.remove();
    }

    private static Map<String, Object> object(Object value, String label) {
        if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException(label + " must be an object");
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) throw new IllegalArgumentException(label + " keys must be text");
            result.put(key, entry.getValue());
        }
        return Map.copyOf(result);
    }

    private static String string(Object value) {
        if (value == null) return "";
        if (!(value instanceof String text) || text.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("plan text field is invalid");
        }
        return text.strip();
    }

    private static double number(
            Object value, double fallback, double minimum, double maximum, String label) {
        if (value == null) return fallback;
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())
                || number.doubleValue() < minimum || number.doubleValue() > maximum) {
            throw new IllegalArgumentException(label + " must be in " + minimum + ".." + maximum);
        }
        return number.doubleValue();
    }

    private static List<String> strings(Object value, int maximumItems, int maximumLength) {
        if (value == null) return List.of();
        if (!(value instanceof List<?> list) || list.size() > maximumItems) {
            throw new IllegalArgumentException("evidenceReferences has too many entries");
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) result.add(optional(string(item), maximumLength));
        return List.copyOf(result);
    }

    private static void exactKeys(Map<String, Object> values, List<String> allowed, String label) {
        for (String key : values.keySet()) {
            if (!allowed.contains(key)) throw new IllegalArgumentException(label + " contains unknown field " + key);
        }
    }

    private static Map<String, Object> targetMap(ResolvedTarget target) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("xuid", target.xuid());
        values.put("connectionId", target.connectionId());
        values.put("displayName", target.displayName());
        values.put("tenantId", target.tenantId());
        values.put("proxyId", target.proxyId());
        values.put("backend", target.backend());
        values.put("clientProtocol", target.clientProtocol());
        values.put("backendProtocol", target.backendProtocol());
        values.put("joinedWorld", target.joinedWorld());
        values.put("transferInProgress", target.transferInProgress());
        return Map.copyOf(values);
    }

    private static Map<String, Object> resultMap(ControlActionResult result) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("requestId", result.requestId().toString());
        values.put("status", result.status().name());
        values.put("success", result.successful());
        values.put("reason", result.reason());
        values.put("result", result.result());
        values.put("startedAt", result.startedAt().toString());
        values.put("completedAt", result.completedAt().toString());
        values.put("durationMillis", result.durationMillis());
        values.put("auditReference", result.auditReference());
        return Map.copyOf(values);
    }

    private static ControlRole role(String value) {
        ControlRole parsed = ControlRole.parse(value);
        return parsed == ControlRole.TENANT ? ControlRole.OPERATOR : parsed;
    }

    private static ActionType action(String value) {
        try {
            return ActionType.valueOf(required(value, "action", 64).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown typed OniControl action");
        }
    }

    private static String digest(String token) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String required(String value, String label, int maximum) {
        String clean = value(value).trim();
        if (clean.isBlank() || clean.length() > maximum || clean.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(label + " is required and must be at most " + maximum + " characters");
        }
        return clean;
    }

    private static String optional(String value, int maximum) {
        String clean = value(value).trim();
        if (clean.length() > maximum || clean.indexOf('\0') >= 0) throw new IllegalArgumentException("value is too long");
        return clean;
    }

    private static String value(String value) { return value == null ? "" : value; }

    private static String safe(String value) {
        String message = value == null || value.isBlank() ? "unknown failure" : value;
        return message.replaceAll("(?i)(secret|token|authorization|jwt)[=: ][^ ,}]+", "$1=<redacted>");
    }

    private record Preview(
            String actor,
            ControlRole role,
            ActionType action,
            ValidatedActionPayload payload,
            ResolvedTarget target,
            String reason,
            String revision,
            Instant expires
    ) {}

    private record PlanPreview(String actor, ControlRole role, ControlActionPlan plan, Instant expires) {
    }
}
