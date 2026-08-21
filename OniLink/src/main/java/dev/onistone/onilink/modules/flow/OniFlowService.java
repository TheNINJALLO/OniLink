package dev.onistone.onilink.modules.flow;

import dev.onistone.onilink.modules.ScopedRecords;
import dev.onistone.onilink.platform.actions.ActionRegistry;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Deterministic workflow engine that accepts only registered typed actions. */
public final class OniFlowService extends ScopedRecords implements AutoCloseable {
    private static final Set<String> TRIGGERS = Set.of("EVENT", "SCHEDULE", "MANUAL", "WEBHOOK_INTERNAL");
    private static final Set<String> STEP_TYPES = Set.of("ACTION", "DELAY", "CONDITION", "APPROVAL", "SEQUENCE", "PARALLEL");
    private static final Set<String> FAILURE_POLICIES = Set.of("STOP", "CONTINUE", "RETRY", "COMPENSATE_WHEN_POSSIBLE");

    private final ActionRegistry actions;
    private final BoundedEventBus events;
    private final ExecutorService executor;
    private final Semaphore concurrency;
    private final int maxWorkflows;
    private final int maxSteps;
    private final int maxParallelBranches;
    private final int maxExecutionSeconds;
    private final Map<String, AtomicBoolean> cancellations = new ConcurrentHashMap<>();
    private final Map<String, Instant> scheduledAt = new ConcurrentHashMap<>();
    private final List<AutoCloseable> eventSubscriptions = new ArrayList<>();
    private final ScheduledExecutorService scheduler;

    public OniFlowService(
            PlatformDatabase database,
            ActionRegistry actions,
            BoundedEventBus events,
            ExecutorService executor,
            int maxWorkflows,
            int maxSteps,
            int maxParallelBranches,
            int maxExecutionSeconds,
            int maxConcurrentExecutions,
            boolean active
    ) {
        super(database);
        this.actions = actions;
        this.events = events;
        this.executor = executor;
        this.maxWorkflows = maxWorkflows;
        this.maxSteps = maxSteps;
        this.maxParallelBranches = maxParallelBranches;
        this.maxExecutionSeconds = maxExecutionSeconds;
        this.concurrency = new Semaphore(maxConcurrentExecutions);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "onilink-flow-scheduler");
            thread.setDaemon(true);
            return thread;
        });
        if (active) {
            for (OniEventType type : OniEventType.values()) {
                eventSubscriptions.add(events.subscribe(type, this::onEvent));
            }
            scheduler.scheduleWithFixedDelay(this::runSchedulesSafely, 1, 1, TimeUnit.SECONDS);
        }
    }

    public List<Map<String, Object>> list(PlatformDatabase.Scope scope) {
        return views(database.list(scope, "workflow", maxWorkflows));
    }

    public Map<String, Object> save(PlatformDatabase.Scope scope, Map<String, Object> workflow, Long revision) {
        if (database.list(scope, "workflow", maxWorkflows + 1).size() >= maxWorkflows
                && (workflow.get("id") == null || database.get(scope, "workflow", String.valueOf(workflow.get("id"))).isEmpty())) {
            throw new IllegalStateException("workflow limit reached");
        }
        Map<String, Object> validated = validate(workflow);
        String workflowId = id((String) validated.get("id"));
        return view(database.put(scope, "workflow", workflowId, revision, validated));
    }

    public Map<String, Object> validate(Map<String, Object> input) {
        String name = required(input, "name", 120);
        String trigger = required(input, "trigger", 32).toUpperCase(Locale.ROOT);
        if (!TRIGGERS.contains(trigger)) throw new IllegalArgumentException("unsupported workflow trigger");
        String failurePolicy = String.valueOf(input.getOrDefault("failurePolicy", "STOP")).toUpperCase(Locale.ROOT);
        if (!FAILURE_POLICIES.contains(failurePolicy)) throw new IllegalArgumentException("unsupported failure policy");
        List<Map<String, Object>> steps = steps(input.get("steps"), 0);
        if (steps.isEmpty()) throw new IllegalArgumentException("workflow requires at least one step");
        if (count(steps) > maxSteps) throw new IllegalArgumentException("workflow exceeds maximum steps");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id(input.get("id") == null ? null : String.valueOf(input.get("id"))));
        result.put("name", name);
        result.put("description", String.valueOf(input.getOrDefault("description", "")));
        result.put("enabled", bool(input.get("enabled"), false));
        result.put("trigger", trigger);
        Map<String, Object> triggerConfig = map(input.get("triggerConfig"));
        validateTrigger(trigger, triggerConfig);
        result.put("triggerConfig", triggerConfig);
        result.put("conditions", list(input.get("conditions")));
        result.put("steps", steps);
        result.put("failurePolicy", failurePolicy);
        result.put("approvalPolicy", String.valueOf(input.getOrDefault("approvalPolicy", "NONE")));
        result.put("timeoutSeconds", Math.max(1, Math.min(maxExecutionSeconds,
                longValue(input.get("timeoutSeconds"), maxExecutionSeconds))));
        result.put("concurrencyPolicy", String.valueOf(input.getOrDefault("concurrencyPolicy", "ALLOW")));
        return Map.copyOf(result);
    }

    public Map<String, Object> dryRun(PlatformDatabase.Scope scope, String workflowId) {
        PlatformDatabase.StoredRecord workflow = database.get(scope, "workflow", id(workflowId))
                .orElseThrow(() -> new IllegalArgumentException("unknown workflow"));
        List<Map<String, Object>> steps = steps(workflow.value().get("steps"), 0);
        return Map.of("valid", true, "workflowId", workflow.id(), "workflowRevision", workflow.revision(),
                "stepCount", count(steps), "actions", actionIds(steps), "willExecute", false);
    }

    public Map<String, Object> run(
            PlatformDatabase.Scope scope, String workflowId, ActionRegistry.Context context, String idempotencyKey
    ) {
        PlatformDatabase.StoredRecord workflow = database.get(scope, "workflow", id(workflowId))
                .orElseThrow(() -> new IllegalArgumentException("unknown workflow"));
        if (!bool(workflow.value().get("enabled"), false)) throw new IllegalStateException("workflow is disabled");
        String executionId = UUID.randomUUID().toString();
        Map<String, Object> pending = new LinkedHashMap<>();
        pending.put("workflowId", workflow.id());
        pending.put("workflowRevision", workflow.revision());
        pending.put("workflowSnapshot", workflow.value());
        pending.put("status", "QUEUED");
        pending.put("startedAt", Instant.now().toString());
        pending.put("correlationId", context.correlationId());
        database.put(scope, "workflow-execution", executionId, null, pending);
        AtomicBoolean cancelled = new AtomicBoolean();
        cancellations.put(executionId, cancelled);
        executor.execute(() -> execute(scope, workflow, executionId, context,
                idempotencyKey == null || idempotencyKey.isBlank() ? executionId : idempotencyKey,
                cancelled, false));
        return Map.of("executionId", executionId, "status", "QUEUED", "workflowRevision", workflow.revision());
    }

    public Map<String, Object> cancel(PlatformDatabase.Scope scope, String executionId) {
        database.get(scope, "workflow-execution", id(executionId))
                .orElseThrow(() -> new IllegalArgumentException("unknown execution"));
        AtomicBoolean cancellation = cancellations.get(executionId);
        if (cancellation != null) {
            cancellation.set(true);
            return Map.of("executionId", executionId, "cancelRequested", true);
        }
        PlatformDatabase.StoredRecord current = database.get(scope, "workflow-execution", id(executionId)).orElseThrow();
        if ("WAITING_APPROVAL".equals(current.value().get("status"))) {
            finish(scope, executionId, "CANCELLED", List.of(), "");
            return Map.of("executionId", executionId, "cancelRequested", true);
        }
        return Map.of("executionId", executionId, "cancelRequested", false);
    }

    public Map<String, Object> approve(PlatformDatabase.Scope scope, String executionId, String actor) {
        PlatformDatabase.StoredRecord execution = database.get(scope, "workflow-execution", id(executionId))
                .orElseThrow(() -> new IllegalArgumentException("unknown execution"));
        if (!"WAITING_APPROVAL".equals(execution.value().get("status"))) {
            throw new IllegalStateException("execution is not waiting for approval");
        }
        String workflowId = String.valueOf(execution.value().get("workflowId"));
        Map<String, Object> snapshot = map(execution.value().get("workflowSnapshot"));
        if (snapshot.isEmpty()) throw new IllegalStateException("the frozen workflow revision is unavailable");
        long workflowRevision = longValue(execution.value().get("workflowRevision"), -1);
        PlatformDatabase.StoredRecord workflow = new PlatformDatabase.StoredRecord(
                scope, "workflow", workflowId, workflowRevision, snapshot,
                execution.createdAt(), execution.updatedAt());
        Map<String, Object> queued = new LinkedHashMap<>(execution.value());
        queued.put("status", "QUEUED");
        queued.put("approvedBy", actor);
        queued.put("approvedAt", Instant.now().toString());
        database.put(scope, "workflow-execution", execution.id(), execution.revision(), queued);
        AtomicBoolean cancelled = new AtomicBoolean();
        cancellations.put(execution.id(), cancelled);
        ActionRegistry.Context context = new ActionRegistry.Context(
                actor, "owner", scope.tenantId(), scope.proxyId(),
                String.valueOf(execution.value().get("correlationId")));
        executor.execute(() -> execute(scope, workflow, execution.id(), context, execution.id(), cancelled, true));
        return Map.of("executionId", execution.id(), "status", "QUEUED", "approved", true);
    }

    public List<Map<String, Object>> executions(PlatformDatabase.Scope scope) {
        return views(database.list(scope, "workflow-execution", 1_000));
    }

    private void execute(
            PlatformDatabase.Scope scope,
            PlatformDatabase.StoredRecord workflow,
            String executionId,
            ActionRegistry.Context context,
            String idempotencyKey,
            AtomicBoolean cancelled,
            boolean approvalGranted
    ) {
        if (!concurrency.tryAcquire()) {
            finish(scope, executionId, "REJECTED", List.of(), "execution concurrency limit reached");
            return;
        }
        try {
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(
                    longValue(workflow.value().get("timeoutSeconds"), maxExecutionSeconds));
            List<Map<String, Object>> results = new ArrayList<>();
            String status = executeSteps(steps(workflow.value().get("steps"), 0), context, idempotencyKey,
                    cancelled, deadline, results, String.valueOf(workflow.value().get("failurePolicy")),
                    approvalGranted);
            finish(scope, executionId, status, results, "");
            if ("WAITING_APPROVAL".equals(status)) {
                events.publish(OniEvent.of(OniEventType.PUSH_NOTIFICATION_REQUESTED,
                        scope.tenantId(), scope.proxyId(), Map.of(
                                "topic", "WORKFLOW_APPROVAL_REQUIRED", "executionId", executionId)));
            }
        } catch (RuntimeException failure) {
            finish(scope, executionId, "FAILED", List.of(), safe(failure));
        } finally {
            cancellations.remove(executionId);
            concurrency.release();
        }
    }

    private String executeSteps(
            List<Map<String, Object>> steps,
            ActionRegistry.Context context,
            String idempotencyKey,
            AtomicBoolean cancelled,
            long deadline,
            List<Map<String, Object>> results,
            String failurePolicy,
            boolean approvalGranted
    ) {
        for (int index = 0; index < steps.size(); index++) {
            if (cancelled.get()) return "CANCELLED";
            if (System.nanoTime() > deadline) return "TIMED_OUT";
            Map<String, Object> step = steps.get(index);
            String type = String.valueOf(step.get("type"));
            try {
                switch (type) {
                    case "ACTION" -> {
                        String action = required(step, "action", 64).toUpperCase(Locale.ROOT);
                        ActionRegistry.Result result = actions.execute(action, context, map(step.get("input")),
                                idempotencyKey == null ? null : idempotencyKey + ":" + index);
                        results.add(Map.of("index", index, "type", type, "status", "COMPLETED",
                                "action", action, "result", result.value()));
                    }
                    case "DELAY" -> {
                        long millis = Math.max(0, Math.min(60_000, longValue(step.get("milliseconds"), 0)));
                        try {
                            long remaining = millis;
                            while (remaining > 0 && !cancelled.get() && System.nanoTime() <= deadline) {
                                long slice = Math.min(remaining, 100);
                                Thread.sleep(slice);
                                remaining -= slice;
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            return "CANCELLED";
                        }
                        if (cancelled.get()) return "CANCELLED";
                        if (System.nanoTime() > deadline) return "TIMED_OUT";
                        results.add(Map.of("index", index, "type", type, "status", "COMPLETED"));
                    }
                    case "CONDITION" -> {
                        boolean passes = bool(step.get("value"), false);
                        results.add(Map.of("index", index, "type", type,
                                "status", passes ? "COMPLETED" : "SKIPPED"));
                        if (!passes && bool(step.get("stopWhenFalse"), false)) return "COMPLETED";
                    }
                    case "APPROVAL" -> {
                        if (!approvalGranted && !bool(step.get("approved"), false)) return "WAITING_APPROVAL";
                        results.add(Map.of("index", index, "type", type, "status", "COMPLETED"));
                    }
                    case "SEQUENCE" -> {
                        String nested = executeSteps(steps(step.get("steps"), 1), context, idempotencyKey,
                                cancelled, deadline, results, failurePolicy, approvalGranted);
                        if (!"COMPLETED".equals(nested)) return nested;
                    }
                    case "PARALLEL" -> {
                        List<Map<String, Object>> branches = steps(step.get("steps"), 1);
                        List<CompletableFuture<BranchResult>> futures = new ArrayList<>();
                        String branchFailurePolicy = failurePolicy;
                        for (int branch = 0; branch < branches.size(); branch++) {
                            int branchIndex = branch;
                            Map<String, Object> branchStep = branches.get(branch);
                            futures.add(CompletableFuture.supplyAsync(() -> {
                                List<Map<String, Object>> branchResults = new ArrayList<>();
                                String status = executeSteps(List.of(branchStep), context,
                                        idempotencyKey + ":parallel:" + branchIndex, cancelled, deadline,
                                        branchResults, branchFailurePolicy, approvalGranted);
                                return new BranchResult(status, branchResults);
                            }));
                        }
                        for (CompletableFuture<BranchResult> future : futures) {
                            BranchResult branch = future.join();
                            results.addAll(branch.results());
                            if (!"COMPLETED".equals(branch.status())) return branch.status();
                        }
                    }
                    default -> throw new IllegalArgumentException("unsupported step type " + type);
                }
            } catch (RuntimeException failure) {
                results.add(Map.of("index", index, "type", type, "status", "FAILED", "reason", safe(failure)));
                if ("RETRY".equals(failurePolicy)) {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return "CANCELLED";
                    }
                    index--;
                    failurePolicy = "STOP";
                    continue;
                }
                if (!"CONTINUE".equals(failurePolicy)) return "FAILED";
            }
        }
        return "COMPLETED";
    }

    private void finish(
            PlatformDatabase.Scope scope, String executionId, String status,
            List<Map<String, Object>> results, String failure
    ) {
        PlatformDatabase.StoredRecord current = database.get(scope, "workflow-execution", executionId).orElse(null);
        if (current == null) return;
        Map<String, Object> value = new LinkedHashMap<>(current.value());
        value.put("status", status);
        if (!"WAITING_APPROVAL".equals(status)) value.put("completedAt", Instant.now().toString());
        value.put("steps", List.copyOf(results));
        if (!failure.isBlank()) value.put("failure", failure);
        database.put(scope, "workflow-execution", executionId, current.revision(), value);
    }

    private void onEvent(OniEvent event) {
        PlatformDatabase.Scope scope = PlatformDatabase.Scope.of(event.tenantId(), event.proxyId());
        for (PlatformDatabase.StoredRecord workflow : database.list(scope, "workflow", maxWorkflows)) {
            if (!bool(workflow.value().get("enabled"), false)
                    || !"EVENT".equals(workflow.value().get("trigger"))) continue;
            Map<String, Object> trigger = map(workflow.value().get("triggerConfig"));
            if (!event.type().name().equals(String.valueOf(trigger.get("event")))) continue;
            ActionRegistry.Context context = new ActionRegistry.Context(
                    "system:event", "owner", event.tenantId(), event.proxyId(), event.correlationId());
            run(scope, workflow.id(), context, event.correlationId());
        }
    }

    private void runSchedulesSafely() {
        try {
            Instant now = Instant.now();
            for (PlatformDatabase.StoredRecord workflow : database.listAll("workflow", 50_000)) {
                if (!bool(workflow.value().get("enabled"), false)
                        || !"SCHEDULE".equals(workflow.value().get("trigger"))) continue;
                Map<String, Object> trigger = map(workflow.value().get("triggerConfig"));
                long intervalSeconds = longValue(trigger.get("intervalSeconds"), 0);
                String key = workflow.scope().tenantId() + '/' + workflow.scope().proxyId() + '/' + workflow.id();
                Instant previous = scheduledAt.get(key);
                boolean due = intervalSeconds > 0 && (previous == null || previous.plusSeconds(intervalSeconds).isBefore(now));
                String at = String.valueOf(trigger.getOrDefault("at", ""));
                if (!at.isBlank() && previous == null) due = !Instant.parse(at).isAfter(now);
                if (!due) continue;
                scheduledAt.put(key, now);
                ActionRegistry.Context context = new ActionRegistry.Context(
                        "system:schedule", "owner", workflow.scope().tenantId(), workflow.scope().proxyId(),
                        UUID.randomUUID().toString());
                run(workflow.scope(), workflow.id(), context, "schedule:" + key + ':' + now.getEpochSecond());
            }
        } catch (RuntimeException failure) {
            System.err.println("OniFlow schedule tick failed safely: " + safe(failure));
        }
    }

    private static void validateTrigger(String trigger, Map<String, Object> config) {
        if ("EVENT".equals(trigger)) {
            String event = String.valueOf(config.getOrDefault("event", ""));
            try { OniEventType.valueOf(event); }
            catch (IllegalArgumentException failure) {
                throw new IllegalArgumentException("triggerConfig.event is invalid", failure);
            }
        }
        if ("SCHEDULE".equals(trigger)) {
            long interval = longValue(config.get("intervalSeconds"), 0);
            String at = String.valueOf(config.getOrDefault("at", ""));
            if (interval < 5 && at.isBlank()) {
                throw new IllegalArgumentException("schedule requires intervalSeconds of at least 5 or an ISO-8601 at time");
            }
            if (!at.isBlank()) Instant.parse(at);
        }
    }

    private record BranchResult(String status, List<Map<String, Object>> results) {}

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> steps(Object value, int depth) {
        if (!(value instanceof List<?> list)) throw new IllegalArgumentException("steps must be an array");
        if (depth > 8) throw new IllegalArgumentException("workflow nesting exceeds 8 levels");
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> raw)) throw new IllegalArgumentException("workflow step must be an object");
            Map<String, Object> step = new LinkedHashMap<>();
            raw.forEach((key, entry) -> step.put(String.valueOf(key), entry));
            String type = required(step, "type", 32).toUpperCase(Locale.ROOT);
            if (!STEP_TYPES.contains(type)) throw new IllegalArgumentException("unsupported workflow step " + type);
            step.put("type", type);
            if (("SEQUENCE".equals(type) || "PARALLEL".equals(type))) {
                List<Map<String, Object>> nested = steps(step.get("steps"), depth + 1);
                if ("PARALLEL".equals(type) && nested.size() > maxParallelBranches) {
                    throw new IllegalArgumentException("parallel step exceeds branch limit");
                }
                step.put("steps", nested);
            }
            result.add(Map.copyOf(step));
        }
        return List.copyOf(result);
    }

    private static int count(List<Map<String, Object>> steps) {
        int total = steps.size();
        for (Map<String, Object> step : steps) {
            if (step.get("steps") instanceof List<?> nested) {
                @SuppressWarnings("unchecked") List<Map<String, Object>> child = (List<Map<String, Object>>) nested;
                total += count(child);
            }
        }
        return total;
    }

    private static List<String> actionIds(List<Map<String, Object>> steps) {
        List<String> result = new ArrayList<>();
        for (Map<String, Object> step : steps) {
            if ("ACTION".equals(step.get("type"))) result.add(String.valueOf(step.get("action")));
            if (step.get("steps") instanceof List<?> nested) {
                @SuppressWarnings("unchecked") List<Map<String, Object>> child = (List<Map<String, Object>>) nested;
                result.addAll(actionIds(child));
            }
        }
        return List.copyOf(result);
    }

    @SuppressWarnings("unchecked") private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }
    @SuppressWarnings("unchecked") private static List<Object> list(Object value) {
        return value instanceof List<?> list ? (List<Object>) list : List.of();
    }
    private static String safe(RuntimeException failure) {
        return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
    }

    @Override
    public void close() {
        for (AutoCloseable subscription : eventSubscriptions) {
            try { subscription.close(); } catch (Exception ignored) { }
        }
        cancellations.values().forEach(value -> value.set(true));
        scheduler.shutdownNow();
    }
}
