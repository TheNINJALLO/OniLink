package dev.onistone.onilink.packet;

import dev.onistone.onilink.control.ActionType;
import dev.onistone.onilink.control.ValidatedActionPayload;
import dev.onistone.onilink.protocol.ProtocolRegistry;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OniPacketFactoryTest {
    static Stream<BedrockCodec> codecs() {
        return ProtocolRegistry.createDefault().supportedCodecs().stream();
    }

    @ParameterizedTest(name = "SEND_MESSAGE encodes for protocol {0}")
    @MethodSource("codecs")
    void semanticMessageDryEncodesForEveryRegisteredCodec(BedrockCodec codec) {
        PacketBuildResult result = new OniPacketFactory().buildClientbound(codec, ActionType.SEND_MESSAGE,
                new ValidatedActionPayload(1, Map.of("message", "codec-safe")),
                Vector3f.ZERO, 0, 1);
        assertEquals(PacketBuildResult.Status.SUPPORTED, result.status(), result.reason());
        assertEquals(1, result.packets().size());
        assertTrue(result.encodedBytes() > 0);
    }
}
