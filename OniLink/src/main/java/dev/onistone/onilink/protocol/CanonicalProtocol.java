package dev.onistone.onilink.protocol;

import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.v898.Bedrock_v898;
import org.cloudburstmc.protocol.bedrock.codec.v924.Bedrock_v924;
import org.cloudburstmc.protocol.bedrock.codec.v944.Bedrock_v944;
import org.cloudburstmc.protocol.bedrock.codec.v975.Bedrock_v975;
import org.cloudburstmc.protocol.bedrock.codec.v1001.Bedrock_v1001;
import org.cloudburstmc.protocol.bedrock.codec.v2168.Bedrock_v2168;
import org.cloudburstmc.protocol.bedrock.codec.v2168.Bedrock_v2168_hotfix4;
import org.cloudburstmc.protocol.bedrock.codec.v2169.Bedrock_v2169;
import org.cloudburstmc.protocol.bedrock.codec.v2192.Bedrock_v2192;

import java.util.Optional;

public enum CanonicalProtocol {
    V1_21_130(Bedrock_v898.CODEC),
    V1_26_0(Bedrock_v924.CODEC),
    V1_26_10(Bedrock_v944.CODEC),
    V1_26_20(Bedrock_v975.CODEC),
    V1_26_30(Bedrock_v1001.CODEC),
    // Mojang renumbered at 1.26.40: 1001 -> 2168, not the ~1010 the earlier steps would suggest.
    V1_26_40(Bedrock_v2168.CODEC),
    // 1.26.44 kept protocol 2168 but changed SetScore's wire layout.
    V1_26_44(Bedrock_v2168_hotfix4.CODEC),
    // 1.26.45 restored the original SetScore layout and advanced the protocol to 2169.
    V1_26_45(Bedrock_v2169.CODEC),
    V1_26_50(Bedrock_v2192.CODEC);

    private final BedrockCodec codec;

    CanonicalProtocol(BedrockCodec codec) {
        this.codec = codec;
    }

    public BedrockCodec codec() {
        return codec;
    }

    public int protocolVersion() {
        return codec.getProtocolVersion();
    }

    public String minecraftVersion() {
        return codec.getMinecraftVersion();
    }

    /**
     * The newest client version the proxy speaks. This is what the server list advertises, so
     * anything user-facing that names a version should derive it from here rather than hardcode one.
     */
    public static CanonicalProtocol newest() {
        CanonicalProtocol[] values = values();
        return values[values.length - 1];
    }

    public static CanonicalProtocol fromConfig(String value) {
        if (value == null || value.isBlank() || "auto".equalsIgnoreCase(value.trim())) {
            return null;
        }
        String normalized = value.trim();
        for (CanonicalProtocol protocol : values()) {
            if (protocol.minecraftVersion().equalsIgnoreCase(normalized)
                    || protocol.minecraftVersion().substring(2).equalsIgnoreCase(normalized)) {
                return protocol;
            }
        }
        CanonicalProtocol numericMatch = null;
        for (CanonicalProtocol protocol : values()) {
            if (Integer.toString(protocol.protocolVersion()).equals(normalized)) {
                // When multiple releases announce one protocol, a bare number selects the newest
                // known dialect. Operators can still name 1.26.40 explicitly.
                numericMatch = protocol;
            }
        }
        if (numericMatch != null) {
            return numericMatch;
        }
        throw new IllegalArgumentException("Unsupported backend protocol: " + value);
    }

    public static Optional<CanonicalProtocol> fromProtocolVersion(int protocolVersion) {
        for (CanonicalProtocol protocol : values()) {
            if (protocol.protocolVersion() == protocolVersion) {
                return Optional.of(protocol);
            }
        }
        return Optional.empty();
    }

    /**
     * Refines a negotiated wire protocol using the authenticated login's GameVersion claim.
     * Protocol 2168 alone cannot distinguish 1.26.40 from 1.26.44, whose SetScore layouts differ.
     */
    public static BedrockCodec clientCodec(BedrockCodec negotiatedCodec, String minecraftVersion) {
        if (negotiatedCodec == null) {
            throw new IllegalArgumentException("negotiatedCodec cannot be null");
        }
        if (negotiatedCodec.getProtocolVersion() == 2168 && is12644(minecraftVersion)) {
            return Bedrock_v2168_hotfix4.CODEC;
        }
        return negotiatedCodec;
    }

    private static boolean is12644(String version) {
        if (version == null || version.isBlank()) {
            return false;
        }
        String[] raw = version.trim().split("\\.");
        int offset = raw.length > 0 && "1".equals(raw[0]) ? 1 : 0;
        if (raw.length < offset + 2) {
            return false;
        }
        try {
            return Integer.parseInt(raw[offset]) == 26
                    && Integer.parseInt(raw[offset + 1]) == 44;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }
}
