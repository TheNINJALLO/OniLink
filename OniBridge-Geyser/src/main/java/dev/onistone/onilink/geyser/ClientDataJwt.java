package dev.onistone.onilink.geyser;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Decodes the already-Geyser-validated client-data JWS without trusting any of its identity fields. */
final class ClientDataJwt {
    private static final int MAX_JWT_SIZE = 3_000_000;
    private static final int MAX_PAYLOAD_SIZE = 2_000_000;

    private ClientDataJwt() {
    }

    static String payloadJson(String original) {
        if (original == null || original.isBlank() || original.length() > MAX_JWT_SIZE) {
            throw new IllegalArgumentException("client data JWT size is invalid");
        }
        // Older compatible builds may preserve the decoded payload rather than the compact JWS.
        if (original.charAt(0) == '{') {
            return original;
        }
        int first = original.indexOf('.');
        int second = first < 0 ? -1 : original.indexOf('.', first + 1);
        if (first <= 0 || second <= first + 1 || second != original.lastIndexOf('.') || second == original.length() - 1) {
            throw new IllegalArgumentException("client data JWT framing is invalid");
        }
        String payloadText = original.substring(first + 1, second);
        if (payloadText.indexOf('=') >= 0) {
            throw new IllegalArgumentException("client data JWT payload is not canonical Base64url");
        }
        final byte[] payload;
        try {
            payload = Base64.getUrlDecoder().decode(payloadText);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("client data JWT payload is invalid", exception);
        }
        if (payload.length == 0 || payload.length > MAX_PAYLOAD_SIZE
                || !Base64.getUrlEncoder().withoutPadding().encodeToString(payload).equals(payloadText)) {
            throw new IllegalArgumentException("client data JWT payload is not canonical Base64url");
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(payload)).toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("client data JWT payload is not UTF-8", exception);
        }
    }
}
