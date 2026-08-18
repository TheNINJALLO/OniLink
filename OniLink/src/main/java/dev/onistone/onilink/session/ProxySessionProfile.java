package dev.onistone.onilink.session;

import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import dev.onistone.onilink.protocol.PacketTranslator;
import dev.onistone.onilink.protocol.ProtocolBinding;
import dev.onistone.onilink.protocol.TranslationContext;

public record ProxySessionProfile(
        BedrockCodec clientCodec,
        BedrockCodec canonicalCodec,
        BedrockCodec backendCodec,
        PacketTranslator translator
) {
    public static ProxySessionProfile from(ProtocolBinding binding) {
        return new ProxySessionProfile(
                binding.clientCodec(),
                binding.canonicalCodec(),
                binding.backendCodec(),
                binding.translator()
        );
    }

    public TranslationContext translationContext() {
        return new TranslationContext(clientCodec, canonicalCodec, backendCodec);
    }
}
