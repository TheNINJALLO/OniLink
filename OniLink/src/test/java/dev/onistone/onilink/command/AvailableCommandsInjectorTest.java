package dev.onistone.onilink.command;

import org.cloudburstmc.protocol.bedrock.data.command.CommandData;
import org.cloudburstmc.protocol.bedrock.data.command.CommandOverloadData;
import org.cloudburstmc.protocol.bedrock.data.command.CommandPermission;
import org.cloudburstmc.protocol.bedrock.packet.AvailableCommandsPacket;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AvailableCommandsInjectorTest {
    @Test
    void preservesBackendTreeAndAddsOneRoot() {
        var packet = new AvailableCommandsPacket();
        var backend = command("plugin:complex");
        packet.getCommands().add(backend);
        new AvailableCommandsInjector(ProxyCommandRegistry.defaults(), List.of("default", "survival")).inject(packet);
        assertEquals(2, packet.getCommands().size());
        assertEquals("onilink", packet.getCommands().get(0).getName());
        assertSame(backend, packet.getCommands().get(1));
        assertTrue(packet.getCommands().get(0).getOverloads().length >= ProxyCommands.defaults().size());
    }

    @Test
    void backendRootCollisionIsNotMutated() {
        var packet = new AvailableCommandsPacket();
        var backend = command("onilink");
        packet.getCommands().add(backend);
        new AvailableCommandsInjector(ProxyCommandRegistry.defaults(), List.of("default")).inject(packet);
        assertEquals(1, packet.getCommands().size());
        assertSame(backend, packet.getCommands().get(0));
    }

    @Test
    void visibilityFiltersSubcommandOverloads() {
        var packet = new AvailableCommandsPacket();
        new AvailableCommandsInjector(
                ProxyCommandRegistry.defaults(), List.of("default"), Set.of("hub")::contains, null).inject(packet);
        assertEquals(1, packet.getCommands().get(0).getOverloads().length);
    }

    private static CommandData command(String name) {
        return new CommandData(name, "backend fixture", Set.of(), CommandPermission.ANY, null, List.of(),
                new CommandOverloadData[0]);
    }
}

