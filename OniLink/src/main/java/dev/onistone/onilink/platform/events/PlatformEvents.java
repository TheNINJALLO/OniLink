package dev.onistone.onilink.platform.events;

import java.util.Map;

/** Non-blocking handoff from live sessions to the bounded platform dispatcher. */
public final class PlatformEvents {
    private static volatile BoundedEventBus bus;
    private PlatformEvents() { }
    public static void attach(BoundedEventBus events) { bus = events; }
    public static void detach(BoundedEventBus events) { if (bus == events) bus = null; }
    public static void publish(OniEventType type, String tenant, String proxy, Map<String, Object> data) {
        var target = bus; if (target != null) target.publish(OniEvent.of(type, tenant, proxy, data));
    }
}
