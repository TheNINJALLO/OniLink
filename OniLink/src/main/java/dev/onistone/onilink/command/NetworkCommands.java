package dev.onistone.onilink.command;

import dev.onistone.onilink.allowlist.ProxyAllowlist;
import dev.onistone.onilink.backend.BackendDirectory;
import dev.onistone.onilink.backend.BackendSwitcher;
import dev.onistone.onilink.backend.ProxyConnection;
import dev.onistone.onilink.config.BackendConfig;
import dev.onistone.onilink.permissions.ProxyPermissions;
import dev.onistone.onilink.session.ConnectedPlayerRegistry;
import dev.onistone.onilink.modules.connect.SupportCommandGateway;
import dev.onistone.onilink.platform.persistence.PlatformDatabase;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The commands that act on the network rather than on the caller: {@code /glist}, {@code /send},
 * {@code /alert} and {@code /perm}.
 *
 * <p>Written against {@link CommandSender} so the console and an in-game administrator run exactly
 * the same code. Permission is checked here, at execution, and not only when the command tree is
 * built — hiding a command from autocomplete does not stop a client sending the packet.</p>
 */
public final class NetworkCommands {
    private final ConnectedPlayerRegistry connectedPlayers;
    private final BackendDirectory backendDirectory;
    private final BackendSwitcher switcher;
    private final ProxyPermissions permissions;
    private final ProxyAllowlist allowlist;
    private final ProxyCommandRegistry commandRegistry;
    private final Runnable onPermissionsChanged;
    private volatile PlatformDatabase.Scope scope = PlatformDatabase.Scope.of("provider", "main");

    public NetworkCommands(
            ConnectedPlayerRegistry connectedPlayers,
            BackendDirectory backendDirectory,
            BackendSwitcher switcher,
            ProxyPermissions permissions,
            ProxyCommandRegistry commandRegistry,
            Runnable onPermissionsChanged
    ) {
        this(connectedPlayers, backendDirectory, switcher, permissions, ProxyAllowlist.disabled(),
                commandRegistry, onPermissionsChanged);
    }

    public void setScope(String tenantId, String proxyId) {
        scope = PlatformDatabase.Scope.of(tenantId, proxyId);
    }

    public void support(CommandSender sender, List<String> arguments) {
        if (sender.isConsole() || sender.connection() == null || sender.xuid().isBlank()) {
            sender.sendMessage("The in-game support command requires an authenticated player.");
            return;
        }
        String backend = String.valueOf(sender.connection().backendName());
        SupportCommandGateway.submit(scope, sender.xuid(), sender.name(), backend, arguments, sender::sendMessage);
    }

    public NetworkCommands(
            ConnectedPlayerRegistry connectedPlayers,
            BackendDirectory backendDirectory,
            BackendSwitcher switcher,
            ProxyPermissions permissions,
            ProxyAllowlist allowlist,
            ProxyCommandRegistry commandRegistry,
            Runnable onPermissionsChanged
    ) {
        this.connectedPlayers = connectedPlayers;
        this.backendDirectory = backendDirectory;
        this.switcher = switcher;
        this.permissions = permissions;
        this.allowlist = allowlist == null ? ProxyAllowlist.disabled() : allowlist;
        this.commandRegistry = commandRegistry;
        this.onPermissionsChanged = onPermissionsChanged == null ? () -> {
        } : onPermissionsChanged;
    }

    /**
     * @return false when the sender may not run this command, having been told so
     */
    public boolean authorize(CommandSender sender, String commandName) {
        if (sender.isConsole() || permissions.allows(sender.xuid(), sender.name(), commandName)) {
            return true;
        }
        System.out.printf("Denied /%s from %s (%s): not permitted.%n",
                commandName, sender.name(), sender.xuid());
        sender.sendMessage("You do not have permission to use /" + commandName + ".");
        return false;
    }

    // ------------------------------------------------------------------ glist

    /** Who is online and where, grouped by backend so an empty backend is visible as empty. */
    public void glist(CommandSender sender) {
        if (connectedPlayers == null) {
            sender.sendMessage("The player list is unavailable.");
            return;
        }
        Map<String, List<String>> byBackend = new LinkedHashMap<>();
        for (BackendConfig backend : backendDirectory.backends()) {
            byBackend.put(backend.name(), new ArrayList<>());
        }
        int total = 0;
        for (ProxyConnection player : connectedPlayers.connections()) {
            // Registration happens at login, before a backend has been chosen, so someone who is
            // still handshaking has no backend name to group under yet.
            String backendName = player.backendName() == null ? "connecting" : player.backendName();
            byBackend.computeIfAbsent(backendName, key -> new ArrayList<>())
                    .add(player.clientLogin().authData().displayName());
            total++;
        }
        byBackend.forEach((backendName, names) -> sender.sendMessage(String.format(
                "[%s] (%d): %s",
                backendName,
                names.size(),
                names.isEmpty() ? "-" : String.join(", ", names)
        )));
        sender.sendMessage(total + " player(s) online.");
    }

    // ------------------------------------------------------------------- send

    public void send(CommandSender sender, List<String> arguments) {
        if (arguments.size() < 2) {
            sender.sendMessage("Usage: /send <player|all> <server>");
            return;
        }
        if (connectedPlayers == null) {
            sender.sendMessage("The player list is unavailable.");
            return;
        }
        String targetName = arguments.get(0);
        String backendName = arguments.get(1);
        BackendConfig backend = backendDirectory.find(backendName).orElse(null);
        if (backend == null) {
            sender.sendMessage("Unknown server: " + backendName);
            return;
        }

        if (ProxyPlayerEnum.ALL.equalsIgnoreCase(targetName)) {
            int moved = 0;
            for (ProxyConnection player : connectedPlayers.connections()) {
                if (isInGame(player) && !backend.name().equalsIgnoreCase(String.valueOf(player.backendName()))) {
                    switcher.switchBackend(player, backend);
                    moved++;
                }
            }
            sender.sendMessage("Sending " + moved + " player(s) to " + backend.name() + ".");
            return;
        }

        connectedPlayers.findByName(targetName).ifPresentOrElse(
                target -> {
                    if (!isInGame(target)) {
                        sender.sendMessage(target.clientLogin().authData().displayName()
                                + " is still connecting and cannot be moved yet.");
                        return;
                    }
                    sender.sendMessage(String.format(
                            "Sending %s to %s.",
                            target.clientLogin().authData().displayName(),
                            backend.name()
                    ));
                    switcher.switchBackend(target, backend);
                },
                () -> sender.sendMessage("No player named '" + targetName + "' is online.")
        );
    }

    // ------------------------------------------------------------------ alert

    public void alert(CommandSender sender, String message) {
        if (message == null || message.isBlank()) {
            sender.sendMessage("Usage: /alert <message>");
            return;
        }
        if (connectedPlayers == null) {
            sender.sendMessage("The player list is unavailable.");
            return;
        }
        String broadcast = "[Alert] " + message;
        int delivered = 0;
        for (ProxyConnection player : connectedPlayers.connections()) {
            if (isInGame(player)) {
                BackendSwitcher.sendMessage(player, broadcast);
                delivered++;
            }
        }
        System.out.printf("%s broadcast an alert to %d player(s): %s%n", sender.name(), delivered, message);
        sender.sendMessage("Alert sent to " + delivered + " player(s).");
    }

    // ------------------------------------------------------------------- perm

    /**
     * {@code /perm set|unset|info|list [player] [node]}.
     *
     * <p>The console can always run this, which is what stops a proxy becoming unadministrable: an
     * operator with no {@code permissions.admins} entry grants themselves {@code admin} from the
     * terminal and carries on in game.</p>
     */
    public void permission(CommandSender sender, List<String> arguments) {
        if (arguments.isEmpty()) {
            permissionUsage(sender);
            return;
        }
        String action = arguments.get(0).toLowerCase(Locale.ROOT);
        switch (action) {
            case "list" -> permissionList(sender);
            case "info" -> {
                if (arguments.size() < 2) {
                    sender.sendMessage("Usage: /perm info <player>");
                    return;
                }
                permissionInfo(sender, arguments.get(1));
            }
            case "set", "unset" -> {
                if (arguments.size() < 3) {
                    sender.sendMessage("Usage: /perm " + action + " <player> <node>");
                    return;
                }
                permissionWrite(sender, "set".equals(action), arguments.get(1), arguments.get(2));
            }
            default -> permissionUsage(sender);
        }
    }

    private void permissionUsage(CommandSender sender) {
        sender.sendMessage("Usage: /perm set|unset <player> <node>, /perm info <player>, /perm list");
        sender.sendMessage("Nodes: " + String.join(", ", knownNodes()));
    }

    private void permissionList(CommandSender sender) {
        Map<String, Set<String>> subjects = permissions.subjects();
        if (subjects.isEmpty()) {
            sender.sendMessage("Nobody has been granted anything at runtime.");
        } else {
            subjects.forEach((subject, nodes) ->
                    sender.sendMessage(subject + ": " + String.join(", ", new java.util.TreeSet<>(nodes))));
        }
        Set<String> configured = permissions.config().admins();
        if (!configured.isEmpty()) {
            sender.sendMessage("From config (permissions.admins, not editable here): "
                    + String.join(", ", new java.util.TreeSet<>(configured)));
        }
    }

    private void permissionInfo(CommandSender sender, String subject) {
        Set<String> nodes = permissions.nodesOf(subject);
        sender.sendMessage(subject + (nodes.isEmpty()
                ? " has no runtime permissions."
                : ": " + String.join(", ", new java.util.TreeSet<>(nodes))));
        // Resolved answers matter more than the raw nodes: the config grants are invisible above,
        // and an "admin" node makes every other line redundant.
        boolean admin = permissions.isAdmin(subject, subject);
        sender.sendMessage("  administrator: " + admin);
        for (String command : commandNames()) {
            if (permissions.isAdminCommand(command)) {
                sender.sendMessage("  /" + command + ": " + permissions.allows(subject, subject, command));
            }
        }
        for (String backend : backendNames()) {
            if (permissions.isAdminBackend(backend)) {
                sender.sendMessage("  server " + backend + ": "
                        + permissions.mayJoinBackend(subject, subject, backend));
            }
        }
    }

    private void permissionWrite(CommandSender sender, boolean granting, String subject, String node) {
        String normalized = node.trim().toLowerCase(Locale.ROOT);
        if (!knownNodes().contains(normalized)) {
            // A typo would otherwise be stored happily and never take effect, which looks exactly
            // like the permission system being broken.
            sender.sendMessage("Unknown permission node: " + node);
            sender.sendMessage("Nodes: " + String.join(", ", knownNodes()));
            return;
        }
        try {
            boolean changed = granting
                    ? permissions.grant(subject, normalized)
                    : permissions.revoke(subject, normalized);
            if (!changed) {
                sender.sendMessage(granting
                        ? subject + " already has " + normalized + "."
                        : subject + " does not have " + normalized + ".");
                return;
            }
        } catch (IllegalArgumentException exception) {
            sender.sendMessage("Cannot store that: " + exception.getMessage());
            return;
        }
        System.out.printf("%s %s %s for %s.%n",
                sender.name(), granting ? "granted" : "revoked", normalized, subject);
        sender.sendMessage((granting ? "Granted " : "Revoked ") + normalized + " for " + subject + ".");
        // The command tree advertises what a player may use, so it has to be rebuilt for anyone
        // whose access just changed — otherwise the grant only takes effect on their next join.
        onPermissionsChanged.run();
    }

    public List<String> knownNodes() {
        return ProxyPermissions.knownNodes(commandNames(), backendNames());
    }

    // --------------------------------------------------------------- allowlist

    public void allowlist(CommandSender sender, List<String> arguments) {
        String action = arguments.isEmpty() ? "status" : arguments.get(0).toLowerCase(Locale.ROOT);
        switch (action) {
            case "status" -> sender.sendMessage(String.format(
                    "Allowlist is %s with %d XUID(s). File: %s",
                    allowlist.enabled() ? "enabled" : "disabled",
                    allowlist.entries().size(),
                    allowlist.config().file()
            ));
            case "list" -> {
                List<ProxyAllowlist.Entry> entries = allowlist.entries();
                if (entries.isEmpty()) {
                    sender.sendMessage("The proxy allowlist is empty.");
                } else {
                    entries.forEach(entry -> sender.sendMessage(entry.xuid()
                            + (entry.name().isBlank() ? "" : " - " + entry.name())));
                }
            }
            case "add" -> {
                if (arguments.size() < 2) {
                    sender.sendMessage("Usage: /onilink allowlist add <online-player|XUID> [label]");
                    return;
                }
                AllowlistSubject subject = resolveAllowlistSubject(arguments.get(1), true);
                String label = arguments.size() > 2
                        ? String.join(" ", arguments.subList(2, arguments.size()))
                        : subject.name();
                try {
                    boolean changed = allowlist.add(subject.xuid(), label);
                    sender.sendMessage(changed
                            ? "Allow-listed XUID " + subject.xuid() + labelSuffix(label) + "."
                            : "XUID " + subject.xuid() + " is already allow-listed with that label.");
                    if (changed) {
                        System.out.printf("%s allow-listed XUID %s%s.%n",
                                sender.name(), subject.xuid(), labelSuffix(label));
                    }
                } catch (IOException exception) {
                    throw new IllegalStateException("Could not save the allowlist: " + exception.getMessage(), exception);
                }
            }
            case "remove", "delete" -> {
                if (arguments.size() < 2) {
                    sender.sendMessage("Usage: /onilink allowlist remove <online-player|XUID>");
                    return;
                }
                AllowlistSubject subject = resolveAllowlistSubject(arguments.get(1), false);
                try {
                    if (!allowlist.remove(subject.xuid())) {
                        sender.sendMessage("XUID " + subject.xuid() + " is not allow-listed.");
                        return;
                    }
                } catch (IOException exception) {
                    throw new IllegalStateException("Could not save the allowlist: " + exception.getMessage(), exception);
                }
                sender.sendMessage("Removed XUID " + subject.xuid() + " from the allowlist.");
                System.out.printf("%s removed XUID %s from the allowlist.%n", sender.name(), subject.xuid());
                disconnectRemoved(subject.xuid());
            }
            default -> sender.sendMessage(
                    "Usage: /onilink allowlist status|list|add <online-player|XUID> [label]|remove <online-player|XUID>");
        }
    }

    private AllowlistSubject resolveAllowlistSubject(String value, boolean adding) {
        String subject = value == null ? "" : value.trim();
        if (subject.chars().allMatch(character -> character >= '0' && character <= '9') && !subject.isEmpty()) {
            String connectedName = connectedPlayers == null ? "" : connectedPlayers.connections().stream()
                    .filter(connection -> subject.equals(connection.clientLogin().authData().xuid()))
                    .map(connection -> connection.clientLogin().authData().displayName())
                    .findFirst().orElse("");
            return new AllowlistSubject(subject, connectedName);
        }
        if (connectedPlayers != null) {
            ProxyConnection connection = connectedPlayers.findByName(subject).orElse(null);
            if (connection != null) {
                return new AllowlistSubject(
                        connection.clientLogin().authData().xuid(),
                        connection.clientLogin().authData().displayName());
            }
        }
        if (!adding) {
            String knownXuid = allowlist.xuidForLabel(subject);
            if (!knownXuid.isEmpty()) return new AllowlistSubject(knownXuid, subject);
        }
        throw new IllegalArgumentException("Use an authenticated XUID, or the name of a player currently online");
    }

    private void disconnectRemoved(String xuid) {
        if (!allowlist.enabled() || !allowlist.config().disconnectOnRemoval() || connectedPlayers == null) return;
        connectedPlayers.connections().stream()
                .filter(connection -> xuid.equals(connection.clientLogin().authData().xuid()))
                .findFirst()
                .ifPresent(connection -> connection.client().disconnect(allowlist.config().kickMessage()));
    }

    private static String labelSuffix(String label) {
        return label == null || label.isBlank() ? "" : " (" + label.trim() + ")";
    }

    private record AllowlistSubject(String xuid, String name) {
    }

    private List<String> commandNames() {
        if (commandRegistry == null) {
            return List.of();
        }
        return commandRegistry.commands().stream().map(ProxyCommand::name).toList();
    }

    private List<String> backendNames() {
        return List.copyOf(backendDirectory.backendNames());
    }

    /**
     * Whether a player can be moved or messaged. Registration happens at login, so the registry
     * also holds sessions that are still negotiating and have neither a backend to leave nor a
     * codec to encode a message with.
     */
    private static boolean isInGame(ProxyConnection connection) {
        return connection.client().isConnected() && connection.hasClientJoinedWorld();
    }
}
