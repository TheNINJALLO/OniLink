package dev.onistone.onilink.plugin;

import dev.onistone.onilink.platform.events.*;
import java.util.*;
import java.util.function.Consumer;

/** Versioned event API whose registrations can precede the dashboard platform's startup. */
public final class AddonEvents {
    private static BoundedEventBus bus;
    private static final List<Registration> registrations = new ArrayList<>();
    private AddonEvents() { }
    public static synchronized void attach(BoundedEventBus events) {
        if (bus != null) throw new IllegalStateException("addon event source is already attached");
        bus = events;
        registrations.forEach(Registration::attach);
    }
    public static synchronized void detach(BoundedEventBus events) {
        if (bus != events) return;
        registrations.forEach(Registration::detach);
        bus = null;
    }
    static synchronized AutoCloseable subscribe(String tenant, String proxy, OniEventType type, Consumer<OniEvent> handler) {
        if (registrations.size() >= 1024) throw new IllegalStateException("addon event subscription limit reached");
        Objects.requireNonNull(type); Objects.requireNonNull(handler);
        var scope = dev.onistone.onilink.platform.persistence.PlatformDatabase.Scope.of(tenant, proxy);
        Registration registration = new Registration(type, event -> {
            if (scope.tenantId().equals(event.tenantId()) && scope.proxyId().equals(event.proxyId())) handler.accept(event);
        });
        registrations.add(registration); registration.attach();
        return () -> { synchronized (AddonEvents.class) { registration.detach(); registrations.remove(registration); } };
    }
    private static final class Registration {
        private final OniEventType type;
        private final Consumer<OniEvent> handler;
        private AutoCloseable subscription;
        private Registration(OniEventType type, Consumer<OniEvent> handler) { this.type = type; this.handler = handler; }
        void attach() { if (bus != null) subscription = bus.subscribe(type, handler); }
        void detach() { if (subscription != null) try { subscription.close(); } catch (Exception ignored) { } subscription = null; }
    }
}
