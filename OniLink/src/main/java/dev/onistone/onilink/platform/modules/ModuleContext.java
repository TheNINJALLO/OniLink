package dev.onistone.onilink.platform.modules;

import dev.onistone.onilink.platform.actions.ActionRegistry;
import dev.onistone.onilink.platform.events.BoundedEventBus;
import dev.onistone.onilink.platform.persistence.PlatformDatabase;

import java.util.Map;
import java.util.concurrent.Executor;

public record ModuleContext(
        BoundedEventBus events,
        ActionRegistry actions,
        PlatformDatabase database,
        Executor worker,
        Map<String, String> configuration
) {
    public ModuleContext {
        if (events == null || actions == null || database == null || worker == null) {
            throw new IllegalArgumentException("module services are required");
        }
        configuration = Map.copyOf(configuration == null ? Map.of() : configuration);
    }
}
