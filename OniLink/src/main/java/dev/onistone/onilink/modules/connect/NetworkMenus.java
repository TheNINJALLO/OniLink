package dev.onistone.onilink.modules.connect;

import dev.onistone.onilink.backend.ProxyConnection;
import dev.onistone.onilink.control.ControlJson;
import org.cloudburstmc.protocol.bedrock.packet.*;
import java.util.*;
import java.util.function.Consumer;

/** One expiring selection per connection; responses are resolved against the displayed snapshot. */
public final class NetworkMenus {
    private record Pending(int id, long deadline, List<String> options, Consumer<String> selected) { }
    private static final Map<ProxyConnection, Pending> pending = new WeakHashMap<>();
    private static final java.util.concurrent.atomic.AtomicInteger sequence = new java.util.concurrent.atomic.AtomicInteger(0x61000000);
    private NetworkMenus() { }
    public static synchronized void show(ProxyConnection connection, List<String> backends, Consumer<String> selected) {
        pending.entrySet().removeIf(e -> e.getValue().deadline() < System.currentTimeMillis());
        if (pending.size() >= 10000 || backends.size() > 1000) throw new IllegalStateException("server menu capacity exceeded");
        int id = sequence.updateAndGet(value -> value >= 0x61ffffff ? 0x61000000 : value + 1);
        List<String> options = List.copyOf(backends);
        pending.put(connection, new Pending(id, System.currentTimeMillis() + 60000, options, selected));
        var packet = new ModalFormRequestPacket();
        packet.setFormId(id);
        packet.setFormData(ControlJson.encode(Map.of("type", "form", "title", "OniLink servers", "content", "Choose a server to join its queue.",
                "buttons", options.stream().map(name -> Map.of("text", name)).toList())));
        connection.client().sendPacket(packet);
    }
    public static boolean consume(ProxyConnection connection, BedrockPacket packet) {
        if (!(packet instanceof ModalFormResponsePacket response)) return false;
        Pending form;
        synchronized (NetworkMenus.class) {
            form = pending.get(connection);
            if (form == null || form.id() != response.getFormId()) return false;
            pending.remove(connection);
        }
        if (form.deadline() < System.currentTimeMillis() || response.getFormData() == null) return true;
        try {
            int index = Integer.parseInt(response.getFormData().trim());
            if (index >= 0 && index < form.options().size()) form.selected().accept(form.options().get(index));
        } catch (NumberFormatException ignored) { /* Closed forms use null; malformed selections cannot invoke actions. */ }
        return true;
    }
}
