package dev.onistone.onilink.packet;

import dev.onistone.onilink.control.PacketOrigin;

import java.util.Locale;
import java.util.Set;

/** Allowlisted rule predicates; there is no executable expression language. */
public record PacketRuleCondition(
        Set<String> packetTypes,
        Set<String> backends,
        Set<Integer> clientProtocols,
        Set<Integer> backendProtocols,
        Set<Integer> dimensions,
        Set<String> sessionPhases,
        Set<PacketOrigin> origins,
        Set<String> xuids,
        Set<String> connectionIds,
        Boolean joinedWorld,
        Boolean transferring
) {
    public PacketRuleCondition {
        packetTypes = cleaned(packetTypes, false);
        backends = cleaned(backends, true);
        clientProtocols = Set.copyOf(clientProtocols == null ? Set.of() : clientProtocols);
        backendProtocols = Set.copyOf(backendProtocols == null ? Set.of() : backendProtocols);
        dimensions = Set.copyOf(dimensions == null ? Set.of() : dimensions);
        sessionPhases = cleaned(sessionPhases, true);
        origins = Set.copyOf(origins == null ? Set.of() : origins);
        xuids = cleaned(xuids, true);
        connectionIds = cleaned(connectionIds, true);
        if (packetTypes.isEmpty()) throw new IllegalArgumentException("a packet rule requires at least one packet type");
        for (String type : packetTypes) {
            if (!type.matches("[A-Za-z][A-Za-z0-9]{1,127}Packet")) {
                throw new IllegalArgumentException("invalid allowlisted packet class name " + type);
            }
        }
    }

    boolean matches(PacketContext context, String packetType) {
        return packetTypes.contains(packetType)
                && (backends.isEmpty() || backends.contains(context.backendName().toLowerCase(Locale.ROOT)))
                && (clientProtocols.isEmpty() || clientProtocols.contains(context.clientProtocol()))
                && (backendProtocols.isEmpty() || backendProtocols.contains(context.backendProtocol()))
                && (dimensions.isEmpty() || dimensions.contains(context.dimension()))
                && (sessionPhases.isEmpty() || sessionPhases.contains(context.sessionPhase().toLowerCase(Locale.ROOT)))
                && (origins.isEmpty() || origins.contains(context.origin()))
                && (xuids.isEmpty() || xuids.contains(context.xuid().toLowerCase(Locale.ROOT)))
                && (connectionIds.isEmpty() || connectionIds.contains(context.connectionId().toLowerCase(Locale.ROOT)))
                && (joinedWorld == null || joinedWorld == context.joinedWorld())
                && (transferring == null || transferring == context.transferring());
    }

    private static Set<String> cleaned(Set<String> input, boolean lower) {
        if (input == null || input.isEmpty()) return Set.of();
        java.util.LinkedHashSet<String> result = new java.util.LinkedHashSet<>();
        for (String value : input) {
            if (value == null || value.isBlank() || value.length() > 128) {
                throw new IllegalArgumentException("rule condition contains an invalid string");
            }
            String clean = value.trim();
            result.add(lower ? clean.toLowerCase(Locale.ROOT) : clean);
        }
        return Set.copyOf(result);
    }
}
