package dev.onistone.onilink.command;

import org.cloudburstmc.protocol.bedrock.packet.CommandRequestPacket;
import dev.onistone.onilink.config.CommandsConfig;

import java.util.Optional;
import java.util.Set;

/**
 * Decides whether a command line the client sent is the proxy's to run or the backend's.
 *
 * <p>Only the exact {@code /onilink} root is eligible for local handling. Bare names and unknown
 * subcommands are forwarded byte-for-byte to the backend.</p>
 */
public final class ProxyCommandInterceptor {
    private static final String ROOT_COMMAND = "onilink";
    private final ProxyCommandRegistry registry;
    private final Set<String> passthrough;

    /** Keeps the collision-resistant root for the proxy. */
    public ProxyCommandInterceptor(ProxyCommandRegistry registry) {
        this(registry, Set.of(), CommandsConfig.DEFAULT_QUALIFIER);
    }

    /**
     * @param passthrough command roots this backend has explicitly taken over
     * @param qualifier   ignored migration value; top-level aliases are never enabled
     */
    public ProxyCommandInterceptor(ProxyCommandRegistry registry, Set<String> passthrough, String qualifier) {
        if (registry == null) {
            throw new IllegalArgumentException("registry cannot be null");
        }
        this.registry = registry;
        this.passthrough = passthrough == null ? Set.of() : Set.copyOf(passthrough);
    }

    public CommandInterception intercept(CommandRequestPacket packet) {
        String commandLine = packet.getCommand();
        String name = ProxyCommandRegistry.commandName(commandLine);

        if (!ROOT_COMMAND.equals(name) || passthrough.contains(ROOT_COMMAND)) {
            return new CommandInterception.Forward(commandLine);
        }
        String trimmed = commandLine == null ? "" : commandLine.trim();
        if (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        int rootSeparator = trimmed.indexOf(' ');
        if (rootSeparator < 0 || rootSeparator + 1 >= trimmed.length()) {
            return new CommandInterception.Forward(commandLine);
        }
        String subcommandLine = trimmed.substring(rootSeparator + 1).trim();
        Optional<ProxyCommand> rootedCommand = registry.find(subcommandLine);
        if (rootedCommand.isEmpty()) {
            return new CommandInterception.Forward(commandLine);
        }
        return new CommandInterception.Consumed(rootedCommand.get(), "/" + subcommandLine);
    }
}
