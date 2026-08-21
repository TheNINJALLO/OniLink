package dev.onistone.onilink.platform;

import dev.onistone.onilink.platform.actions.ActionRegistry;
import dev.onistone.onilink.platform.events.BoundedEventBus;
import dev.onistone.onilink.platform.events.OniEvent;
import dev.onistone.onilink.platform.events.OniEventType;
import dev.onistone.onilink.platform.modules.ModuleCapabilities;
import dev.onistone.onilink.platform.modules.ModuleContext;
import dev.onistone.onilink.platform.modules.ModuleHealth;
import dev.onistone.onilink.platform.modules.ModuleManager;
import dev.onistone.onilink.platform.modules.OniModule;
import dev.onistone.onilink.platform.persistence.PlatformDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExpansionPlatformTest {
    @TempDir Path directory;

    @Test
    void modulesStartInDependencyOrderAndIsolateUnrelatedFailures() throws Exception {
        List<String> lifecycle = new ArrayList<>();
        try (PlatformDatabase database = new PlatformDatabase(directory);
             BoundedEventBus events = new BoundedEventBus(8, Runnable::run)) {
            ModuleContext context = new ModuleContext(events, new ActionRegistry(), database, Runnable::run, Map.of());
            ModuleManager manager = new ModuleManager(context);
            manager.register(new TestModule("dependent", Set.of("foundation"), false, lifecycle));
            manager.register(new TestModule("failed", Set.of(), true, lifecycle));
            manager.register(new TestModule("foundation", Set.of(), false, lifecycle));
            manager.register(new TestModule("independent", Set.of(), false, lifecycle));
            manager.startAll();

            assertTrue(lifecycle.indexOf("start:foundation") < lifecycle.indexOf("start:dependent"));
            Map<String, String> health = manager.snapshot().stream().collect(java.util.stream.Collectors.toMap(
                    item -> String.valueOf(item.get("id")), item -> String.valueOf(item.get("health"))));
            assertEquals("FAILED", health.get("failed"));
            assertEquals("HEALTHY", health.get("independent"));
            manager.close();
        }
    }

    @Test
    void eventBusIsOrderedBoundedAndSubscriberFailuresAreIsolated() throws Exception {
        List<Runnable> pending = new ArrayList<>();
        List<Integer> received = new ArrayList<>();
        try (BoundedEventBus bus = new BoundedEventBus(2, pending::add)) {
            bus.subscribe(OniEventType.PLAYER_CONNECTED, ignored -> { throw new IllegalStateException("isolated"); });
            bus.subscribe(OniEventType.PLAYER_CONNECTED,
                    event -> received.add(((Number) event.data().get("sequence")).intValue()));
            assertTrue(bus.publish(OniEvent.of(OniEventType.PLAYER_CONNECTED, "tenant-a", "proxy-a",
                    Map.of("sequence", 1))));
            assertTrue(bus.publish(OniEvent.of(OniEventType.PLAYER_CONNECTED, "tenant-a", "proxy-a",
                    Map.of("sequence", 2))));
            assertFalse(bus.publish(OniEvent.of(OniEventType.PLAYER_CONNECTED, "tenant-a", "proxy-a",
                    Map.of("sequence", 3))));
            pending.removeFirst().run();
            assertEquals(List.of(1, 2), received);
            assertEquals(1, bus.metrics().get("dropped"));
        }
    }

    @Test
    void actionsEnforceSchemaRoleAndTenantScopedIdempotency() {
        ActionRegistry registry = new ActionRegistry();
        AtomicInteger invoked = new AtomicInteger();
        registry.register(new ActionRegistry.Descriptor(
                "TEST_ACTION", 1, "admin", ActionRegistry.TenantBehavior.EXACT_SCOPE,
                Set.of("target"), Set.of(), Duration.ofSeconds(1), true, false, true,
                "test.action", (context, input) -> Map.of("count", invoked.incrementAndGet())));
        ActionRegistry.Context viewer = new ActionRegistry.Context("viewer", "viewer", "tenant-a", "proxy", "c1");
        ActionRegistry.Context admin = new ActionRegistry.Context("admin", "admin", "tenant-a", "proxy", "c2");
        ActionRegistry.Context otherTenant = new ActionRegistry.Context("admin", "admin", "tenant-b", "proxy", "c3");

        assertThrows(SecurityException.class,
                () -> registry.execute("TEST_ACTION", viewer, Map.of("target", "one"), "same"));
        assertThrows(IllegalArgumentException.class,
                () -> registry.execute("TEST_ACTION", admin, Map.of("rawPacket", "no"), "same"));
        assertEquals(1, registry.execute("TEST_ACTION", admin, Map.of("target", "one"), "same").value().get("count"));
        assertEquals(1, registry.execute("TEST_ACTION", admin, Map.of("target", "one"), "same").value().get("count"));
        assertThrows(IllegalArgumentException.class,
                () -> registry.execute("TEST_ACTION", admin, Map.of("target", "two"), "same"));
        assertEquals(2, registry.execute("TEST_ACTION", otherTenant, Map.of("target", "one"), "same").value().get("count"));
    }

    @Test
    void databaseMigratesAndRejectsCrossScopeReadsAndStaleRevisions() throws Exception {
        PlatformDatabase.Scope first = PlatformDatabase.Scope.of("tenant-a", "proxy-1");
        PlatformDatabase.Scope second = PlatformDatabase.Scope.of("tenant-b", "proxy-1");
        try (PlatformDatabase database = new PlatformDatabase(directory)) {
            PlatformDatabase.StoredRecord created = database.put(first, "workflow", "nightly", 0L,
                    Map.of("enabled", true));
            assertEquals(1, created.revision());
            assertTrue(database.get(second, "workflow", "nightly").isEmpty());
            assertThrows(PlatformDatabase.RevisionConflict.class,
                    () -> database.put(first, "workflow", "nightly", 0L, Map.of("enabled", false)));
            assertEquals(1, database.listAll("workflow", 10).size());
            assertTrue(database.path().toFile().isFile());
        }
    }

    private static final class TestModule implements OniModule {
        private final String id;
        private final Set<String> dependencies;
        private final boolean fail;
        private final List<String> lifecycle;

        private TestModule(String id, Set<String> dependencies, boolean fail, List<String> lifecycle) {
            this.id = id;
            this.dependencies = dependencies;
            this.fail = fail;
            this.lifecycle = lifecycle;
        }

        @Override public String id() { return id; }
        @Override public Set<String> dependencies() { return dependencies; }
        @Override public boolean enabled() { return true; }
        @Override public void initialize(ModuleContext context) { lifecycle.add("init:" + id); }
        @Override public void start() {
            lifecycle.add("start:" + id);
            if (fail) throw new IllegalStateException("expected failure");
        }
        @Override public ModuleHealth health() { return ModuleHealth.of(ModuleHealth.State.HEALTHY, "ready"); }
        @Override public ModuleCapabilities capabilities() {
            return new ModuleCapabilities("1", Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
                    Set.of(), Set.of(), Set.of(), Map.of());
        }
        @Override public void close() { lifecycle.add("close:" + id); }
    }
}
