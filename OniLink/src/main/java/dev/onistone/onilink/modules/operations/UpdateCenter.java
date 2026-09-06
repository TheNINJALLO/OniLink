package dev.onistone.onilink.modules.operations;

import dev.onistone.onilink.modules.*;
import dev.onistone.onilink.modules.continuity.ContinuityService;
import dev.onistone.onilink.modules.forge.CompatibilityLab;
import dev.onistone.onilink.platform.persistence.PlatformDatabase;
import java.time.*;
import java.util.*;

/** Durable maintenance phases. Interrupted process/filesystem work requires explicit recovery. */
public final class UpdateCenter extends ScopedRecords {
    private static final Set<String> TERMINAL = Set.of("COMPLETED", "ROLLED_BACK", "RECOVERED", "FAILED", "RECOVERY_REQUIRED");
    private final ArtifactStore artifacts;
    private final ManagedServers servers;
    private final ProxyOperations proxy;
    private final ContinuityService continuity;
    public UpdateCenter(PlatformDatabase db, ArtifactStore artifacts, ManagedServers servers, ProxyOperations proxy, ContinuityService continuity) {
        super(db); this.artifacts = artifacts; this.servers = servers; this.proxy = proxy; this.continuity = continuity;
        for (var job : db.listAll("maintenance-job", 50000)) if (!TERMINAL.contains(job.value().get("state"))) {
            var value = new LinkedHashMap<>(job.value());
            value.put("state", "RECOVERY_REQUIRED");
            value.put("error", "Proxy restarted during maintenance; inspect the backend and restore its verified snapshot before resuming.");
            db.put(job.scope(), job.kind(), job.id(), job.revision(), value);
        }
    }
    public Map<String, Object> status(PlatformDatabase.Scope scope) {
        return Map.of("artifacts", artifacts.list(scope), "managedServers", servers.list(scope),
                "jobs", views(database.list(scope, "maintenance-job", 1000)), "labRuns", views(database.list(scope, "compatibility-run", 100)),
                "nativeAcceptance", views(database.list(scope, "native-acceptance", 100)), "nativeRuntime", "Endstone");
    }
    public Map<String, Object> lab(PlatformDatabase.Scope scope, String candidate, String suite) throws Exception {
        if (!"server".equals(artifacts.get(scope, candidate).get("kind"))) throw new IllegalArgumentException("expected server candidate");
        if (!"fixtures".equals(artifacts.get(scope, suite).get("kind"))) throw new IllegalArgumentException("expected fixture archive");
        String release = String.valueOf(artifacts.get(scope, candidate).get("version")).replaceFirst("^(\\d+\\.\\d+\\.\\d+)\\.\\d+$", "$1");
        var result = new LinkedHashMap<>(new CompatibilityLab().runArchive(proxy.protocols(), artifacts.path(scope, suite), release));
        result.putAll(Map.of("serverArtifact", candidate, "fixtureArtifact", suite,
                "protocolGeneration", generation(scope)));
        return view(database.put(scope, "compatibility-run", UUID.randomUUID().toString(), 0L, result));
    }
    public Map<String, Object> acceptNative(PlatformDatabase.Scope scope, Map<String, Object> input, String actor) {
        String artifact = required(input, "artifact", 64);
        var candidate = artifacts.get(scope, artifact);
        if (!"server".equals(candidate.get("kind"))) throw new IllegalArgumentException("expected server artifact");
        String endstone = required(input, "endstoneVersion", 64);
        String evidence = required(input, "evidence", 4096);
        for (String check : List.of("startup", "pluginLoaded", "clientJoin", "movement", "inventory", "crafting", "commands", "packs", "transfers", "shutdown"))
            if (!Boolean.TRUE.equals(input.get(check))) throw new IllegalArgumentException("live acceptance check missing: " + check);
        return view(database.put(scope, "native-acceptance", artifact, null, Map.of("artifact", artifact, "endstoneVersion", endstone,
                "evidence", evidence, "actor", actor, "evidenceType", "operator-attested-live-acceptance", "platform", candidate.get("platform"), "version", candidate.get("version"))));
    }
    public Map<String, Object> preview(PlatformDatabase.Scope scope, String candidate, String backend) {
        var artifact = artifacts.get(scope, candidate);
        var server = servers.server(scope, backend);
        List<String> blockers = new ArrayList<>();
        if (!"server".equals(artifact.get("kind"))) blockers.add("The artifact is not a Bedrock server file set.");
        String platform = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "windows" : "linux";
        if (!platform.equals(artifact.get("platform"))) blockers.add("The archive targets a different operating system.");
        if (!server.eulaAccepted()) blockers.add("The server operator must record EULA acceptance in the managed-server configuration.");
        var acceptance = database.get(scope, "native-acceptance", candidate);
        if (acceptance.isEmpty() || !server.endstoneVersion().equals(acceptance.get().value().get("endstoneVersion")))
            blockers.add("Exact archive and Endstone version need recorded native acceptance evidence.");
        long generation = generation(scope);
        boolean tested = database.list(scope, "compatibility-run", 1000).stream().anyMatch(r -> candidate.equals(r.value().get("serverArtifact"))
                && "PASS".equals(r.value().get("status")) && Boolean.TRUE.equals(r.value().get("coverageComplete"))
                && generation == longValue(r.value().get("protocolGeneration"), -1));
        if (!tested) blockers.add("Complete compatibility fixtures must pass against the active protocol generation.");
        Map<String, Object> protocolChanges;
        String release = String.valueOf(artifact.get("version")).replaceFirst("^(\\d+\\.\\d+\\.\\d+)\\.\\d+$", "$1");
        String currentVersion = proxy.backends().stream().filter(b -> backend.equals(b.get("name"))).map(b -> b.get("health"))
                .filter(Map.class::isInstance).map(Map.class::cast).map(h -> String.valueOf(h.get("advertisedVersion"))).findFirst().orElse("")
                .replaceFirst("^(\\d+\\.\\d+\\.\\d+)\\.\\d+$", "$1");
        try { protocolChanges = new dev.onistone.onilink.modules.forge.ForgeService(proxy.protocols()).diff(currentVersion, release); }
        catch (IllegalArgumentException missing) { protocolChanges = Map.of("available", false); blockers.add("Current and candidate release codecs must be registered before protocol changes can be reviewed."); }
        return Map.of("artifact", artifact, "backend", backend, "endstoneVersion", server.endstoneVersion(), "ready", blockers.isEmpty(), "protocolChanges", protocolChanges,
                "blockers", blockers, "steps", List.of("Confirm evacuation", "Stop Endstone", "Verify complete backup", "Install server files",
                        "Start Endstone", "Check process and proxy health", "Confirm player returns"));
    }
    public synchronized Map<String, Object> deploy(PlatformDatabase.Scope scope, String candidate, String backend, String requestId, String actor) throws Exception {
        String jobId = UUID.fromString(requestId).toString();
        var existing = database.get(scope, "maintenance-job", jobId);
        if (existing.isPresent()) {
            if (!candidate.equals(existing.get().value().get("artifact")) || !backend.equals(existing.get().value().get("backend")))
                throw new IllegalArgumentException("idempotency key was used for another deployment");
            return view(existing.get());
        }
        if (!Boolean.TRUE.equals(preview(scope, candidate, backend).get("ready"))) throw new IllegalStateException("deployment preview has blockers");
        requireNoConflictingJob(scope, backend, jobId);
        artifacts.path(scope, candidate);
        Map<String, Object> value = new LinkedHashMap<>();
        value.putAll(Map.of("artifact", candidate, "backend", backend, "actor", actor, "state", "DRAINING", "phase", "DRAINING",
                "deadline", Instant.now().plusSeconds(180).toString(), "snapshot", "", "protocolGeneration", generation(scope)));
        value.put("previousVersion", proxy.backends().stream().filter(b -> backend.equals(b.get("name"))).map(b -> b.get("health"))
                .filter(Map.class::isInstance).map(Map.class::cast).map(h -> String.valueOf(h.get("advertisedVersion"))).findFirst().orElse(""));
        var saved = database.put(scope, "maintenance-job", jobId, 0L, value);
        try {
            proxy.players().stream().filter(p -> backend.equals(p.get("backend"))).forEach(p -> proxy.message(String.valueOf(p.get("xuid")), "Maintenance is starting. Moving you to the lobby; you will be returned when the server is ready."));
            continuity.drain(scope, backend, actor);
        } catch (Exception failure) { return fail(saved, failure, false); }
        return view(saved);
    }
    public synchronized void tick(PlatformDatabase.Scope scope) {
        for (var job : database.list(scope, "maintenance-job", 1000)) {
            String state = String.valueOf(job.value().get("state"));
            if (TERMINAL.contains(state)) continue;
            try { advance(job); } catch (Exception failure) { fail(database.get(scope, job.kind(), job.id()).orElseThrow(), failure, !state.equals("DRAINING")); }
        }
    }
    private void advance(PlatformDatabase.StoredRecord job) throws Exception {
        var scope = job.scope();
        String backend = String.valueOf(job.value().get("backend"));
        String phase = String.valueOf(job.value().get("phase"));
        var server = servers.server(scope, backend);
        if (Instant.parse(String.valueOf(job.value().get("deadline"))).isBefore(Instant.now())) throw new IllegalStateException("maintenance phase deadline exceeded");
        if (phase.equals("DRAINING")) {
            if (!continuity.drained(scope, backend)) return;
            proxy.setBackendEnabled(backend, false, longValue(proxy.backendRegistry().get("revision"), -1));
            transition(job, "STOPPING", Map.of());
        } else if (phase.equals("STOPPING")) {
            if (proxy.players().stream().anyMatch(p -> backend.equals(p.get("backend")) || backend.equals(p.get("switchTarget")))) {
                continuity.drain(scope, backend, String.valueOf(job.value().get("actor")));
                return;
            }
            transition(job, "STOP_IN_PROGRESS", Map.of());
            servers.stop(server);
            transition(current(job), "WAIT_STOPPED", Map.of("stoppedAt", Instant.now().toString()));
        } else if (phase.equals("WAIT_STOPPED")) {
            if (servers.healthy(server) || !backendStopped(job, backend)) return;
            transition(current(job), Boolean.TRUE.equals(job.value().get("rollback")) ? "RESTORING" : "BACKING_UP", Map.of());
        } else if (phase.equals("BACKING_UP")) {
            requireEmpty(backend);
            transition(job, "BACKUP_IN_PROGRESS", Map.of());
            String snapshot = servers.backup(server, job.id());
            transition(current(job), "INSTALLING", Map.of("snapshot", snapshot));
        } else if (phase.equals("INSTALLING")) {
            requireEmpty(backend);
            if (generation(scope) != longValue(job.value().get("protocolGeneration"), -1)) throw new IllegalStateException("protocol generation changed during maintenance");
            transition(job, "INSTALL_IN_PROGRESS", Map.of());
            servers.install(server, artifacts.path(scope, String.valueOf(job.value().get("artifact"))));
            transition(current(job), "STARTING", Map.of());
        } else if (phase.equals("RESTORING")) {
            requireEmpty(backend);
            transition(job, "RESTORE_IN_PROGRESS", Map.of());
            servers.restore(server, String.valueOf(job.value().get("snapshot")));
            transition(current(job), "STARTING", Map.of());
        } else if (phase.equals("STARTING")) {
            transition(job, "START_IN_PROGRESS", Map.of());
            servers.start(server);
            transition(current(job), "HEALTH_CHECK", Map.of("startedAt", Instant.now().toString()));
        } else if (phase.equals("HEALTH_CHECK")) {
            if (!servers.healthy(server) || !backendHealthy(job, backend)) return;
            proxy.setBackendEnabled(backend, true, longValue(proxy.backendRegistry().get("revision"), -1));
            continuity.returnPlayers(scope, backend, String.valueOf(job.value().get("actor")));
            transition(job, "RETURNING", Map.of());
        } else if (phase.equals("RETURNING")) {
            continuity.reconcile(scope);
            boolean draining = proxy.backendRegistry().get("backends") instanceof List<?> list && list.stream().anyMatch(r -> r instanceof Map<?, ?> b
                    && backend.equals(b.get("name")) && Boolean.TRUE.equals(b.get("draining")));
            var returns = database.list(scope, "drain-reservation", 10000).stream().filter(r -> backend.equals(r.value().get("backend"))).toList();
            if (returns.stream().anyMatch(r -> !"RETURN_WHEN_ONLINE".equals(r.value().get("state"))) || draining) return;
            transition(job, "COMPLETED", Map.of());
        } else throw new IllegalStateException("maintenance operation was interrupted and requires recovery");
    }
    public synchronized Map<String, Object> rollback(PlatformDatabase.Scope scope, String jobId, long revision) throws Exception {
        var job = database.get(scope, "maintenance-job", jobId).orElseThrow();
        if (job.revision() != revision) throw new IllegalStateException("job changed; refresh before rollback");
        if (!TERMINAL.contains(job.value().get("state"))) throw new IllegalStateException("wait for active maintenance to finish before rollback");
        String snapshot = Objects.toString(job.value().get("snapshot"), "");
        if (snapshot.isBlank()) throw new IllegalStateException("no completed snapshot exists; inspect the backend manually");
        String backend = String.valueOf(job.value().get("backend"));
        requireNoConflictingJob(scope, backend, jobId);
        try {
            continuity.drain(scope, backend, String.valueOf(job.value().get("actor")));
            transition(job, "DRAINING", Map.of("rollback", true, "manualRecovery", false));
            return view(current(job));
        } catch (Exception failure) { return fail(current(job), failure, true); }
    }
    public synchronized Map<String, Object> recover(PlatformDatabase.Scope scope, Map<String, Object> input, String actor) throws Exception {
        String jobId = required(input, "job", 64);
        var job = database.get(scope, "maintenance-job", jobId).orElseThrow();
        if (job.revision() != longValue(input.get("revision"), -1)) throw new IllegalStateException("job changed; refresh before recovery");
        if (!"RECOVERY_REQUIRED".equals(job.value().get("state"))) throw new IllegalStateException("job does not require manual recovery");
        String backend = String.valueOf(job.value().get("backend"));
        requireNoConflictingJob(scope, backend, jobId);
        String version = required(input, "version", 64);
        String evidence = required(input, "evidence", 4096);
        if (!version.matches("\\d+\\.\\d+\\.\\d+(?:\\.\\d+)?")) throw new IllegalArgumentException("recovered version must be a numeric Bedrock release");
        String release = version.replaceFirst("^(\\d+\\.\\d+\\.\\d+)\\.\\d+$", "$1");
        new dev.onistone.onilink.modules.forge.ForgeService(proxy.protocols()).diff(release, release);
        var server = servers.server(scope, backend);
        if (!server.endstoneVersion().equals(required(input, "endstoneVersion", 64))) throw new IllegalArgumentException("recovery evidence must match the configured Endstone version");
        if (!servers.healthy(server)) throw new IllegalStateException("repair and start the managed Endstone server before recording recovery");
        // The next tick requires a fresh matching backend probe before enabling admission or returns.
        transition(job, "HEALTH_CHECK", Map.of("manualRecovery", true, "rollback", false,
                "recoveredVersion", version, "recoveryEvidence", evidence, "recoveryActor", actor,
                "startedAt", Instant.now().toString()));
        return view(current(job));
    }
    private void requireNoConflictingJob(PlatformDatabase.Scope scope, String backend, String jobId) {
        for (var other : database.list(scope, "maintenance-job", 1000)) if (!other.id().equals(jobId)
                && backend.equals(other.value().get("backend"))
                && (!TERMINAL.contains(other.value().get("state")) || "RECOVERY_REQUIRED".equals(other.value().get("state"))))
            throw new IllegalStateException("backend already has an active or unresolved maintenance job");
    }
    private PlatformDatabase.StoredRecord current(PlatformDatabase.StoredRecord job) { return database.get(job.scope(), job.kind(), job.id()).orElseThrow(); }
    private void transition(PlatformDatabase.StoredRecord job, String phase, Map<String, Object> extra) {
        var value = new LinkedHashMap<>(job.value());
        String state = phase.equals("COMPLETED") && Boolean.TRUE.equals(value.get("manualRecovery")) ? "RECOVERED"
                : phase.equals("COMPLETED") && Boolean.TRUE.equals(value.get("rollback")) ? "ROLLED_BACK" : phase;
        value.putAll(Map.of("state", state, "phase", phase, "deadline", Instant.now().plusSeconds(600).toString()));
        value.putAll(extra);
        database.put(job.scope(), job.kind(), job.id(), job.revision(), value);
        database.put(job.scope(), "maintenance-history", UUID.randomUUID().toString(), 0L, Map.of("job", job.id(), "phase", phase));
    }
    private Map<String, Object> fail(PlatformDatabase.StoredRecord job, Exception failure, boolean recovery) {
        var value = new LinkedHashMap<>(job.value());
        value.put("state", recovery ? "RECOVERY_REQUIRED" : "FAILED");
        value.put("error", failure.getClass().getSimpleName() + ": " + String.valueOf(failure.getMessage()));
        return view(database.put(job.scope(), job.kind(), job.id(), job.revision(), value));
    }
    private void requireEmpty(String backend) {
        if (proxy.players().stream().anyMatch(p -> backend.equals(p.get("backend")) || backend.equals(p.get("switchTarget"))))
            throw new IllegalStateException("backend still has connected players or pending transfers");
    }
    private boolean backendHealthy(PlatformDatabase.StoredRecord job, String backend) {
        String version = Boolean.TRUE.equals(job.value().get("manualRecovery")) ? String.valueOf(job.value().get("recoveredVersion"))
                : Boolean.TRUE.equals(job.value().get("rollback")) ? String.valueOf(job.value().get("previousVersion"))
                : String.valueOf(artifacts.get(job.scope(), String.valueOf(job.value().get("artifact"))).get("version"));
        String release = version.replaceFirst("^(\\d+\\.\\d+\\.\\d+)\\.\\d+$", "$1");
        Instant started = Instant.parse(String.valueOf(job.value().get("startedAt")));
        return proxy.backends().stream().anyMatch(b -> {
            if (!backend.equals(b.get("name")) || !(b.get("health") instanceof Map<?, ?> health) || !"online".equals(health.get("status"))) return false;
            String advertised = String.valueOf(health.get("advertisedVersion"));
            try { return !release.isBlank() && (advertised.equals(release) || advertised.startsWith(release + "."))
                    && Instant.parse(String.valueOf(health.get("checkedAt"))).isAfter(started); }
            catch (RuntimeException invalid) { return false; }
        });
    }
    private boolean backendStopped(PlatformDatabase.StoredRecord job, String backend) {
        Instant stopped = Instant.parse(String.valueOf(job.value().get("stoppedAt")));
        return proxy.backends().stream().anyMatch(b -> {
            if (!backend.equals(b.get("name")) || !(b.get("health") instanceof Map<?, ?> health) || !"offline".equals(health.get("status"))) return false;
            try { return Instant.parse(String.valueOf(health.get("checkedAt"))).isAfter(stopped); }
            catch (RuntimeException invalid) { return false; }
        });
    }
    private long generation(PlatformDatabase.Scope scope) { return database.get(scope, "protocol-generation", "current").map(PlatformDatabase.StoredRecord::revision).orElse(0L); }
}
