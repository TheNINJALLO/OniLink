package dev.onistone.onilink.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.ReferenceCountUtil;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.BedrockPacketDefinition;
import org.cloudburstmc.protocol.bedrock.data.PacketRecipient;
import org.cloudburstmc.protocol.bedrock.data.ScoreInfo;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises real source bytes, the selected translator, and target decoding without skipped packets. */
class ReleaseTranslationTest {
    static List<ProtocolBinding> routes() {
        ProtocolRegistry registry = ProtocolRegistry.createDefault();
        return List.of(
                binding(registry, CanonicalProtocol.V1_26_45, CanonicalProtocol.V1_26_30),
                binding(registry, CanonicalProtocol.V1_26_45, CanonicalProtocol.V1_26_40),
                binding(registry, CanonicalProtocol.V1_26_45, CanonicalProtocol.V1_26_44),
                binding(registry, CanonicalProtocol.V1_26_45, CanonicalProtocol.V1_26_45),
                binding(registry, CanonicalProtocol.V1_26_40, CanonicalProtocol.V1_26_45),
                binding(registry, CanonicalProtocol.V1_26_44, CanonicalProtocol.V1_26_45),
                binding(registry, CanonicalProtocol.V1_26_50, CanonicalProtocol.V1_26_45)
        );
    }

    private static ProtocolBinding binding(ProtocolRegistry registry, CanonicalProtocol client, CanonicalProtocol backend) {
        return registry.findBinding(client.codec(), backend.protocolVersion(), backend.minecraftVersion()).orElseThrow();
    }

    @ParameterizedTest
    @MethodSource("routes")
    void joinAndMovementPacketsSurviveBothRelayDirections(ProtocolBinding binding) {
        for (String name : CrossProtocolCoverageTest.JOIN_CRITICAL) {
            for (boolean serverbound : new boolean[]{true, false}) {
                BedrockCodec source = serverbound ? binding.clientCodec() : binding.backendCodec();
                BedrockPacketDefinition<?> definition = null;
                for (int id = 0; id < 512; id++) {
                    var candidate = source.getPacketDefinition(id);
                    if (candidate != null && candidate.getFactory().get().getClass().getSimpleName().equals(name)) {
                        definition = candidate;
                        break;
                    }
                }
                assertNotNull(definition, name);
                PacketRecipient direction = serverbound ? PacketRecipient.SERVER : PacketRecipient.CLIENT;
                if (definition.getRecipient() != null && definition.getRecipient() != PacketRecipient.BOTH
                        && definition.getRecipient() != direction) continue;
                BedrockPacket packet = PacketPopulator.populate(definition.getFactory().get());
                BedrockPacket result = relay(binding, serverbound, packet);
                try {
                    assertEquals(packet.getClass(), result.getClass(), name);
                } finally {
                    ReferenceCountUtil.release(packet);
                    ReferenceCountUtil.release(result);
                }
            }
        }
    }

    @ParameterizedTest
    @MethodSource("routes")
    void populatedScoreboardUpdatesAndRemovalsKeepTheirMeaning(ProtocolBinding binding) {
        for (SetScorePacket.Action action : SetScorePacket.Action.values()) {
            SetScorePacket scores = new SetScorePacket();
            scores.setAction(action);
            scores.getInfos().add(action == SetScorePacket.Action.SET
                    ? new ScoreInfo(7L, "objective", 42, "Alice") : new ScoreInfo(7L, "objective", 0));
            SetScorePacket result = (SetScorePacket) relay(binding, false, scores);
            assertEquals(scores.getInfos(), result.getInfos());
        }
    }

    private static BedrockPacket relay(ProtocolBinding binding, boolean serverbound, BedrockPacket packet) {
        BedrockCodec source = serverbound ? binding.clientCodec() : binding.backendCodec();
        BedrockCodec target = serverbound ? binding.backendCodec() : binding.clientCodec();
        ByteBuf incoming = Unpooled.buffer();
        ByteBuf outgoing = Unpooled.buffer();
        BedrockPacket decoded = null;
        try {
            source.tryEncode(CrossProtocolCoverageTest.helperFor(source), incoming, packet);
            decoded = source.tryDecode(CrossProtocolCoverageTest.helperFor(source), incoming,
                    source.getPacketDefinition(packet.getClass()).getId());
            assertEquals(packet.getClass(), decoded.getClass(), "source decode failed");
            assertFalse(incoming.isReadable(), "source left unread bytes");
            TranslationContext context = new TranslationContext(binding.clientCodec(), binding.canonicalCodec(), binding.backendCodec());
            BedrockPacket translated = serverbound ? binding.translator().translateServerbound(decoded, context)
                    : binding.translator().translateClientbound(decoded, context);
            assertNotNull(translated, "join-critical packet was dropped");
            target.tryEncode(CrossProtocolCoverageTest.helperFor(target), outgoing, translated);
            BedrockPacket result = target.tryDecode(CrossProtocolCoverageTest.helperFor(target), outgoing,
                    target.getPacketDefinition(translated.getClass()).getId());
            assertFalse(outgoing.isReadable(), "target left unread bytes");
            return result;
        } finally {
            incoming.release();
            outgoing.release();
            ReferenceCountUtil.release(decoded);
        }
    }
}
