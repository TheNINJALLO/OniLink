package dev.onistone.onilink.command;

import org.cloudburstmc.protocol.bedrock.packet.CommandRequestPacket;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class ProxyCommandInterceptorTest {
    @Test
    void consumesOnlyNamespacedProxyCommands() {
        var interceptor = new ProxyCommandInterceptor(ProxyCommandRegistry.defaults());
        var consumed = assertInstanceOf(CommandInterception.Consumed.class,
                interceptor.intercept(command("/onilink server survival")));
        assertEquals("server", consumed.command().name());
        assertEquals(java.util.List.of("survival"), CommandArguments.split(consumed.originalCommandLine()));
        assertInstanceOf(CommandInterception.Consumed.class, interceptor.intercept(command("onilink hub")));
    }

    @Test
    void forwardsEveryBareOrUnknownBackendCommandExactly() {
        var interceptor = new ProxyCommandInterceptor(ProxyCommandRegistry.defaults());
        for (String value : new String[]{"/server survival", "/hub", "/say  hello", "/plugin:unicode café", "/onilink unknown x"}) {
            var forwarded = assertInstanceOf(CommandInterception.Forward.class, interceptor.intercept(command(value)));
            assertEquals(value, forwarded.originalCommandLine());
        }
    }

    @Test
    void explicitRootCollisionGivesBackendAuthority() {
        var interceptor = new ProxyCommandInterceptor(ProxyCommandRegistry.defaults(), Set.of("onilink"), "");
        assertInstanceOf(CommandInterception.Forward.class, interceptor.intercept(command("/onilink status")));
    }

    private static CommandRequestPacket command(String value) {
        var packet = new CommandRequestPacket();
        packet.setCommand(value);
        return packet;
    }
}

