package dev.onistone.onilink.platform.modules;

import java.util.Set;

/** Declarative module whose service lifecycle is owned by the expansion runtime. */
public final class ConfiguredModule implements OniModule {
    private final String id;
    private final boolean enabled;
    private final Set<String> dependencies;
    private final ModuleCapabilities capabilities;
    private volatile ModuleHealth health;

    public ConfiguredModule(
            String id, boolean enabled, Set<String> dependencies, ModuleCapabilities capabilities
    ) {
        this.id = id;
        this.enabled = enabled;
        this.dependencies = Set.copyOf(dependencies);
        this.capabilities = capabilities;
        this.health = ModuleHealth.of(enabled ? ModuleHealth.State.STARTING : ModuleHealth.State.DISABLED,
                enabled ? "Waiting to start" : "Disabled by configuration");
    }

    @Override public String id() { return id; }
    @Override public Set<String> dependencies() { return dependencies; }
    @Override public boolean enabled() { return enabled; }
    @Override public void initialize(ModuleContext context) {
        if (context == null) throw new IllegalArgumentException("context is required");
    }
    @Override public void start() { health = ModuleHealth.of(ModuleHealth.State.HEALTHY, "Ready"); }
    @Override public ModuleHealth health() { return health; }
    @Override public ModuleCapabilities capabilities() { return capabilities; }
    @Override public void close() { health = ModuleHealth.of(ModuleHealth.State.STOPPED, "Stopped"); }
}
