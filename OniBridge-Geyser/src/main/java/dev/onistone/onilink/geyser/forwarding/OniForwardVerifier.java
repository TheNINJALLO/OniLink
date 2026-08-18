package dev.onistone.onilink.geyser.forwarding;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Strict, dependency-free verifier for the OniForward v2 login claim. */
public final class OniForwardVerifier {
    public static final int ENCODING_VERSION = 1;
    public static final int PROTOCOL_VERSION = 2;
    private static final byte[] MAGIC = {'O', 'N', 'I', 'F'};
    private static final int FIELD_COUNT = 14;

    public record Claims(
            int protocolVersion,
            String keyId,
            String proxyId,
            String bridgeId,
            String backendName,
            String sessionId,
            String nonce,
            String playerName,
            String xuid,
            UUID proxyUuid,
            String realIp,
            int realPort,
            long issuedAtMs,
            long expiresAtMs
    ) {
    }

    public record Key(String id, byte[] secret) {
        public Key {
            Objects.requireNonNull(id, "id");
            secret = Objects.requireNonNull(secret, "secret").clone();
        }

        @Override
        public byte[] secret() {
            return secret.clone();
        }
    }

    public record KeyRing(Key active, Key previous) {
        public KeyRing {
            Objects.requireNonNull(active, "active");
        }
    }

    public record Validation(
            String expectedPlayerName,
            String expectedXuid,
            String expectedBridgeId,
            String expectedBackendName,
            long nowMs,
            long maximumLifetimeMs,
            long allowedClockSkewMs,
            int maximumTokenSize
    ) {
    }

    public record Result(Claims claims, String error) {
        public boolean valid() {
            return claims != null;
        }
    }

    private final KeyRing keys;

    public OniForwardVerifier(KeyRing keys) {
        this.keys = Objects.requireNonNull(keys, "keys");
    }

    public Result verify(String token, Validation validation) {
        Objects.requireNonNull(validation, "validation");
        if (token == null || token.isEmpty() || token.length() > validation.maximumTokenSize()) {
            return failure("token size is invalid");
        }
        int separator = token.indexOf('.');
        if (separator <= 0 || separator != token.lastIndexOf('.')) {
            return failure("token framing is invalid");
        }
        if (token.indexOf('=') >= 0) {
            return failure("padded Base64 is not canonical");
        }

        final byte[] payload;
        final byte[] suppliedSignature;
        try {
            String payloadText = token.substring(0, separator);
            String signatureText = token.substring(separator + 1);
            payload = Base64.getUrlDecoder().decode(payloadText);
            suppliedSignature = Base64.getUrlDecoder().decode(signatureText);
            if (!base64Url(payload).equals(payloadText) || !base64Url(suppliedSignature).equals(signatureText)) {
                return failure("token Base64 is not canonical");
            }
        } catch (IllegalArgumentException exception) {
            return failure("token Base64 is invalid");
        }

        Result decoded = decode(payload);
        if (!decoded.valid()) {
            return decoded;
        }
        Claims claims = decoded.claims();
        Key key = claims.keyId().equals(keys.active().id())
                ? keys.active()
                : keys.previous() != null && claims.keyId().equals(keys.previous().id()) ? keys.previous() : null;
        if (key == null || suppliedSignature.length != 32) {
            return failure("signature mismatch or unknown key");
        }
        byte[] expectedSignature = hmac(key.secret, payload);
        try {
            if (!MessageDigest.isEqual(expectedSignature, suppliedSignature)) {
                return failure("signature mismatch or unknown key");
            }
        } finally {
            java.util.Arrays.fill(expectedSignature, (byte) 0);
        }

        if (!claims.playerName().equalsIgnoreCase(validation.expectedPlayerName())) {
            return failure("player name mismatch");
        }
        if (!claims.xuid().equals(validation.expectedXuid())) {
            return failure("XUID mismatch");
        }
        if (!claims.bridgeId().equals(validation.expectedBridgeId())
                || !claims.backendName().equals(validation.expectedBackendName())) {
            return failure("bridge or backend mismatch");
        }
        if (claims.expiresAtMs() < claims.issuedAtMs()
                || claims.expiresAtMs() - claims.issuedAtMs() > validation.maximumLifetimeMs()) {
            return failure("token lifetime exceeds policy");
        }
        if (claims.issuedAtMs() > validation.nowMs() + validation.allowedClockSkewMs()) {
            return failure("token was issued in the future");
        }
        if (claims.expiresAtMs() < validation.nowMs() - validation.allowedClockSkewMs()) {
            return failure("token is expired");
        }
        return decoded;
    }

    private static Result decode(byte[] payload) {
        if (payload.length < 6) {
            return failure("invalid payload magic");
        }
        for (int index = 0; index < MAGIC.length; index++) {
            if (payload[index] != MAGIC[index]) {
                return failure("invalid payload magic");
            }
        }
        if (Byte.toUnsignedInt(payload[4]) != ENCODING_VERSION) {
            return failure("unsupported encoding version");
        }
        if (Byte.toUnsignedInt(payload[5]) != FIELD_COUNT) {
            return failure("missing or extra fields");
        }

        String[] fields = new String[FIELD_COUNT];
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload, 6, payload.length - 6))) {
            for (int expectedId = 1; expectedId <= FIELD_COUNT; expectedId++) {
                int id = input.readUnsignedByte();
                if (id != expectedId) {
                    return failure(id < expectedId ? "duplicate or unordered field" : "missing or unordered field");
                }
                int length = input.readUnsignedShort();
                if (length == 0 || length > input.available()) {
                    return failure("empty or truncated field");
                }
                fields[expectedId - 1] = strictUtf8(input.readNBytes(length));
            }
            if (input.available() != 0) {
                return failure("trailing payload bytes");
            }
        } catch (IOException exception) {
            return failure("claim encoding is invalid");
        }

        try {
            int protocolVersion = parseUnsignedInt(fields[0]);
            if (protocolVersion != PROTOCOL_VERSION) {
                return failure("unsupported protocol version");
            }
            if (!asciiDigits(fields[8])) {
                return failure("XUID is invalid");
            }
            UUID proxyUuid = UUID.fromString(fields[9]);
            if (!proxyUuid.toString().equals(fields[9].toLowerCase(Locale.ROOT))) {
                return failure("proxy UUID is not canonical");
            }
            InetAddress realAddress = TrustedProxyMatcher.parseLiteral(fields[10]);
            if (realAddress == null) {
                return failure("real IP is invalid");
            }
            int port = parseUnsignedInt(fields[11]);
            if (port > 65_535) {
                return failure("real port is invalid");
            }
            return new Result(new Claims(
                    protocolVersion, fields[1], fields[2], fields[3], fields[4], fields[5], fields[6], fields[7],
                    fields[8], proxyUuid, fields[10], port, Long.parseLong(fields[12]), Long.parseLong(fields[13])), "");
        } catch (IllegalArgumentException exception) {
            return failure("claim encoding is invalid");
        }
    }

    private static int parseUnsignedInt(String value) {
        if (!asciiDigits(value)) {
            throw new NumberFormatException("not unsigned decimal");
        }
        return Integer.parseInt(value);
    }

    private static boolean asciiDigits(String value) {
        return value != null && !value.isEmpty() && value.chars().allMatch(character -> character >= '0' && character <= '9');
    }

    private static String strictUtf8(byte[] value) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(value)).toString();
    }

    private static byte[] hmac(byte[] secret, byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(payload);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", exception);
        }
    }

    private static String base64Url(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static Result failure(String error) {
        return new Result(null, error);
    }
}
