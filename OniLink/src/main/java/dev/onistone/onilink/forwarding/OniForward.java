package dev.onistone.onilink.forwarding;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Canonical OniForward v2 encoder and verifier. */
public final class OniForward {
    public static final int ENCODING_VERSION = 1;
    public static final int PROTOCOL_VERSION = 2;
    private static final byte[] MAGIC = {'O', 'N', 'I', 'F'};
    private static final int FIELD_COUNT = 14;

    private OniForward() {
    }

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
        public Claims {
            Objects.requireNonNull(keyId);
            Objects.requireNonNull(proxyId);
            Objects.requireNonNull(bridgeId);
            Objects.requireNonNull(backendName);
            Objects.requireNonNull(sessionId);
            Objects.requireNonNull(nonce);
            Objects.requireNonNull(playerName);
            Objects.requireNonNull(xuid);
            Objects.requireNonNull(proxyUuid);
            Objects.requireNonNull(realIp);
        }
    }

    public record Key(String id, byte[] secret) {
        public Key {
            Objects.requireNonNull(id);
            secret = Objects.requireNonNull(secret).clone();
        }

        @Override
        public byte[] secret() {
            return secret.clone();
        }
    }

    public record KeyRing(Key active, Key previous) {
        public KeyRing {
            Objects.requireNonNull(active);
        }
    }

    public record Validation(
            String expectedPlayerName,
            String expectedBridgeId,
            String expectedBackendName,
            long nowMs,
            long maximumLifetimeMs,
            long allowedClockSkewMs,
            int maximumTokenSize
    ) {
        public static Validation defaults(String playerName, String bridgeId, String backendName, Clock clock) {
            return new Validation(playerName, bridgeId, backendName, clock.millis(), 10_000, 2_000, 4_096);
        }
    }

    public record Result(Claims claims, String error) {
        public boolean valid() {
            return claims != null;
        }
    }

    public static String sign(Claims claims, Key key) {
        if (!claims.keyId().equals(key.id()) || key.secret.length == 0) {
            throw new IllegalArgumentException("Claims key ID and signing key must match");
        }
        byte[] payload = encode(claims);
        return encodeBase64(payload) + "." + encodeBase64(hmac(key.secret, payload));
    }

    public static Result verify(String token, KeyRing keys, Validation validation) {
        if (token == null || token.isEmpty() || token.length() > validation.maximumTokenSize()) {
            return failure("token size is invalid");
        }
        int separator = token.indexOf('.');
        if (separator <= 0 || separator != token.lastIndexOf('.')) {
            return failure("token framing is invalid");
        }
        final byte[] payload;
        final byte[] signature;
        try {
            if (token.indexOf('=') >= 0) {
                return failure("padded Base64 is not canonical");
            }
            payload = Base64.getUrlDecoder().decode(token.substring(0, separator));
            signature = Base64.getUrlDecoder().decode(token.substring(separator + 1));
            if (!encodeBase64(payload).equals(token.substring(0, separator))
                    || !encodeBase64(signature).equals(token.substring(separator + 1))) {
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
        if (key == null || signature.length != 32 || !MessageDigest.isEqual(hmac(key.secret, payload), signature)) {
            return failure("signature mismatch or unknown key");
        }
        if (claims.protocolVersion() != PROTOCOL_VERSION) return failure("unsupported protocol version");
        if (claims.xuid().isEmpty() || !claims.xuid().chars().allMatch(c -> c >= '0' && c <= '9')) return failure("XUID is invalid");
        if (claims.realPort() < 0 || claims.realPort() > 65_535) return failure("real port is invalid");
        if (!validIpLiteral(claims.realIp())) return failure("real IP is invalid");
        if (!claims.playerName().equalsIgnoreCase(validation.expectedPlayerName())) return failure("player name mismatch");
        if (!claims.bridgeId().equals(validation.expectedBridgeId()) || !claims.backendName().equals(validation.expectedBackendName())) return failure("bridge or backend mismatch");
        if (claims.expiresAtMs() < claims.issuedAtMs() || claims.expiresAtMs() - claims.issuedAtMs() > validation.maximumLifetimeMs()) return failure("token lifetime exceeds policy");
        if (claims.issuedAtMs() > validation.nowMs() + validation.allowedClockSkewMs()) return failure("token was issued in the future");
        if (claims.expiresAtMs() < validation.nowMs() - validation.allowedClockSkewMs()) return failure("token is expired");
        return decoded;
    }

    private static byte[] encode(Claims claims) {
        String[] values = {
                Integer.toString(claims.protocolVersion()), claims.keyId(), claims.proxyId(), claims.bridgeId(),
                claims.backendName(), claims.sessionId(), claims.nonce(), claims.playerName(), claims.xuid(),
                claims.proxyUuid().toString(), claims.realIp(), Integer.toString(claims.realPort()),
                Long.toString(claims.issuedAtMs()), Long.toString(claims.expiresAtMs())
        };
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.writeBytes(MAGIC);
        output.write(ENCODING_VERSION);
        output.write(FIELD_COUNT);
        for (int index = 0; index < values.length; index++) {
            byte[] bytes = values[index].getBytes(StandardCharsets.UTF_8);
            if (bytes.length == 0 || bytes.length > 65_535) throw new IllegalArgumentException("invalid OniForward field length");
            output.write(index + 1);
            output.write(bytes.length >>> 8);
            output.write(bytes.length);
            output.writeBytes(bytes);
        }
        return output.toByteArray();
    }

    private static Result decode(byte[] payload) {
        if (payload.length < 6 || !Arrays.equals(Arrays.copyOf(payload, 4), MAGIC)) return failure("invalid payload magic");
        if (Byte.toUnsignedInt(payload[4]) != ENCODING_VERSION) return failure("unsupported encoding version");
        if (Byte.toUnsignedInt(payload[5]) != FIELD_COUNT) return failure("missing or extra fields");
        String[] values = new String[FIELD_COUNT];
        int offset = 6;
        try {
            for (int expected = 1; expected <= FIELD_COUNT; expected++) {
                if (offset + 3 > payload.length) return failure("truncated field header");
                int id = Byte.toUnsignedInt(payload[offset++]);
                if (id != expected) return failure(id < expected ? "duplicate or unordered field" : "missing or unordered field");
                int length = Byte.toUnsignedInt(payload[offset++]) << 8 | Byte.toUnsignedInt(payload[offset++]);
                if (length == 0 || offset + length > payload.length) return failure("empty or truncated field");
                values[expected - 1] = new String(payload, offset, length, StandardCharsets.UTF_8);
                if (!Arrays.equals(values[expected - 1].getBytes(StandardCharsets.UTF_8), Arrays.copyOfRange(payload, offset, offset + length))) return failure("field is not canonical UTF-8");
                offset += length;
            }
            if (offset != payload.length) return failure("trailing payload bytes");
            Claims claims = new Claims(
                    Integer.parseInt(values[0]), values[1], values[2], values[3], values[4], values[5], values[6],
                    values[7], values[8], parseUuid(values[9]), values[10], Integer.parseInt(values[11]),
                    Long.parseLong(values[12]), Long.parseLong(values[13]));
            return new Result(claims, "");
        } catch (IllegalArgumentException exception) {
            return failure("claim encoding is invalid");
        }
    }

    private static UUID parseUuid(String value) {
        UUID uuid = UUID.fromString(value);
        if (!uuid.toString().equals(value.toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("non-canonical UUID");
        return uuid;
    }

    private static boolean validIpLiteral(String value) {
        if (value.indexOf(':') < 0) {
            String[] parts = value.split("\\.", -1);
            if (parts.length != 4) return false;
            for (String part : parts) {
                if (part.isEmpty() || part.length() > 3 || !part.chars().allMatch(Character::isDigit)) return false;
                int number = Integer.parseInt(part);
                if (number > 255 || (part.length() > 1 && part.charAt(0) == '0')) return false;
            }
            return true;
        }
        if (!value.chars().allMatch(c -> c == ':' || c == '.' || Character.digit(c, 16) >= 0)) return false;
        try {
            InetAddress parsed = InetAddress.getByName(value);
            return parsed instanceof Inet6Address || parsed instanceof Inet4Address;
        } catch (UnknownHostException exception) {
            return false;
        }
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

    private static String encodeBase64(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static Result failure(String error) {
        return new Result(null, error);
    }
}
