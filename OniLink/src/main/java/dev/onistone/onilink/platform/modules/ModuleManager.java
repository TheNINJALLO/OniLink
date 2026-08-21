package dev.onistone.onilink.platform.modules;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Starts modules topologically and isolates failures that are not hard dependencies. */
public final class ModuleManager implements AutoCloseable {
    private final ModuleContext context;
    private final Map<String, OniModule> modules = new LinkedHashMap<>();
    private final Map<String, ModuleHealth> health = new LinkedHashMap<>();
    private final List<OniModule> started = new ArrayList<>();

    public ModuleManager(ModuleContext context) {
        this.context = context;
    }

    public synchronized void register(OniModule module) {
        if (module == null || !module.id().matches("[a-z][a-z0-9-]{1,63}")) {
            throw new IllegalArgumentException("module ID is invalid");
        }
        if (modules.putIfAbsent(module.id(), module) != null) {
            throw new IllegalArgumentException("duplicate module " + module.id());
        }
    }

    public synchronized void startAll() {
        for (OniModule module : ordered()) {
            if (!module.enabled()) {
                health.put(module.id(), ModuleHealth.of(ModuleHealth.State.DISABLED, "Disabled by configuration"));
                continue;
            }
            String failedDependency = module.dependencies().stream()
                    .filter(dependency -> health.getOrDefault(dependency,
                            ModuleHealth.of(ModuleHealth.State.FAILED, "Missing dependency")).state()
                            != ModuleHealth.State.HEALTHY)
                    .findFirst().orElse("");
            if (!failedDependency.isBlank()) {
                health.put(module.id(), ModuleHealth.of(ModuleHealth.State.FAILED,
                        "Dependency is unavailable: " + failedDependency));
                continue;
            }
            try {
                health.put(module.id(), ModuleHealth.of(ModuleHealth.State.STARTING, "Starting"));
                module.initialize(context);
                module.start();
                started.add(module);
                health.put(module.id(), module.health());
            } catch (RuntimeException failure) {
                health.put(module.id(), new ModuleHealth(ModuleHealth.State.FAILED,
                        safeMessage(failure), Instant.now()));
                try {
                    module.close();
                } catch (RuntimeException ignored) {
                    // Initialization failure remains isolated from unrelated modules.
                }
            }
        }
    }

    public synchronized List<Map<String, Object>> snapshot() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (OniModule module : modules.values()) {
            ModuleHealth state = health.getOrDefault(module.id(),
                    ModuleHealth.of(ModuleHealth.State.STOPPED, "Not started"));
            ModuleCapabilities capabilities = module.capabilities();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", module.id());
            item.put("version", capabilities.version());
            item.put("enabled", module.enabled());
            item.put("dependencies", module.dependencies());
            item.put("health", state.state().name());
            item.put("message", state.message());
            item.put("checkedAt", state.checkedAt().toString());
            item.put("capabilities", capabilities);
            result.add(Collections.unmodifiableMap(item));
        }
        return List.copyOf(result);
    }

    private List<OniModule> ordered() {
        List<OniModule> result = new ArrayList<>();
        Set<String> visiting = new LinkedHashSet<>();
        Set<String> visited = new LinkedHashSet<>();
        for (OniModule module : modules.values()) visit(module, visiting, visited, result);
        return result;
    }

    private void visit(OniModule module, Set<String> visiting, Set<String> visited, List<OniModule> result) {
        if (visited.contains(module.id())) return;
        if (!visiting.add(module.id())) throw new IllegalStateException("module dependency cycle at " + module.id());
        for (String dependency : module.dependencies()) {
            OniModule required = modules.get(dependency);
            if (required == null) throw new IllegalStateException(
                    "module " + module.id() + " requires missing module " + dependency);
            visit(required, visiting, visited, result);
        }
        visiting.remove(module.id());
        visited.add(module.id());
        result.add(module);
    }

    private static String safeMessage(RuntimeException failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    @Override
    public synchronized void close() {
        List<OniModule> reverse = new ArrayList<>(started);
        Collections.reverse(reverse);
        for (OniModule module : reverse) {
            try {
                module.close();
            } finally {
                health.put(module.id(), ModuleHealth.of(ModuleHealth.State.STOPPED, "Stopped"));
            }
        }
        started.clear();
    }
}
