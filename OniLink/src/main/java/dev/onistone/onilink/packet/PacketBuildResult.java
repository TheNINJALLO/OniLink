package dev.onistone.onilink.packet;

import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;

import java.util.List;

/** Result of validating and dry-encoding packets for one negotiated codec. */
public record PacketBuildResult(Status status, List<BedrockPacket> packets, int encodedBytes, String reason) {
    public enum Status { SUPPORTED, UNSUPPORTED, REJECTED, ENCODE_FAILED }

    public PacketBuildResult {
        if (status == null) throw new IllegalArgumentException("packet build status is required");
        packets = List.copyOf(packets == null ? List.of() : packets);
        reason = reason == null ? "" : reason;
        if (status != Status.SUPPORTED && reason.isBlank()) {
            throw new IllegalArgumentException("an unsuccessful packet build requires a reason");
        }
    }

    public static PacketBuildResult unsupported(String reason) {
        return new PacketBuildResult(Status.UNSUPPORTED, List.of(), 0, reason);
    }

    public static PacketBuildResult rejected(String reason) {
        return new PacketBuildResult(Status.REJECTED, List.of(), 0, reason);
    }
}
