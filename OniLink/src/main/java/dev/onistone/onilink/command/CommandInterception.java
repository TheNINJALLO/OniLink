package dev.onistone.onilink.command;

public sealed interface CommandInterception permits CommandInterception.Consumed, CommandInterception.Forward {
    record Consumed(ProxyCommand command, String originalCommandLine) implements CommandInterception {
    }

    record Forward(String originalCommandLine) implements CommandInterception {
    }
}
