package dev.onistone.onilink.platform.modules;

import java.util.Map;
import java.util.Set;

/** Public, secret-free module manifest exposed to operators and compatibility tooling. */
public record ModuleCapabilities(
        String version,
        Set<String> apiRoutes,
        Set<String> permissions,
        Set<String> eventsProduced,
        Set<String> eventsConsumed,
        Set<String> metrics,
        Set<String> auditCategories,
        Set<String> migrations,
        Set<String> backupScope,
        Map<String, Object> configurationSchema
) {
    public ModuleCapabilities {
        version = version == null || version.isBlank() ? "1" : version;
        apiRoutes = Set.copyOf(apiRoutes == null ? Set.of() : apiRoutes);
        permissions = Set.copyOf(permissions == null ? Set.of() : permissions);
        eventsProduced = Set.copyOf(eventsProduced == null ? Set.of() : eventsProduced);
        eventsConsumed = Set.copyOf(eventsConsumed == null ? Set.of() : eventsConsumed);
        metrics = Set.copyOf(metrics == null ? Set.of() : metrics);
        auditCategories = Set.copyOf(auditCategories == null ? Set.of() : auditCategories);
        migrations = Set.copyOf(migrations == null ? Set.of() : migrations);
        backupScope = Set.copyOf(backupScope == null ? Set.of() : backupScope);
        configurationSchema = Map.copyOf(configurationSchema == null ? Map.of() : configurationSchema);
    }
}
