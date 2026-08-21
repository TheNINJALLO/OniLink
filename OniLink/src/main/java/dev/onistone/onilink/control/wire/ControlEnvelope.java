package dev.onistone.onilink.control.wire;

import dev.onistone.onilink.control.ControlJson;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public record ControlEnvelope(
        int version,
        String keyId,
        String requestId,
        String idempotencyKey,
        long timestamp,
        String nonce,
        String bridgeId,
        String backend,
        String targetXuid,
        String action,
        String payload,
        String signature
) {
    private static final Base64.Encoder BASE64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder BASE64_DECODER = Base64.getUrlDecoder();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<String> FIELDS = Set.of(
            "version", "keyId", "requestId", "idempotencyKey", "timestamp", "nonce", "bridgeId",
            "backend", "targetXuid", "action", "payload", "signature");

    public ControlEnvelope {
        if (version != 1) throw new IllegalArgumentException("unsupported ONICTL version");
        keyId = required(keyId, "keyId", 64);
        requestId = uuid(requestId, "requestId");
        idempotencyKey = required(idempotencyKey, "idempotencyKey", 128);
        nonce = required(nonce, "nonce", 128);
        byte[] nonceBytes = decode(nonce, "nonce");
        if (nonceBytes.length < 16 || nonceBytes.length > 64) throw new IllegalArgumentException("nonce must contain 16..64 bytes");
        bridgeId = required(bridgeId, "bridgeId", 64);
        backend = required(backend, "backend", 64);
        targetXuid = required(targetXuid, "targetXuid", 32);
        action = required(action, "action", 64);
        payload = required(payload, "payload", 262_144);
        decode(payload, "payload");
        signature = signature == null ? "" : signature;
    }

    public static ControlEnvelope signed(
            String keyId,
            String requestId,
            String idempotencyKey,
            String bridgeId,
            String backend,
            String targetXuid,
            String action,
            Map<String, Object> payload,
            byte[] secret
    ) {
        byte[] nonce = new byte[16];
        RANDOM.nextBytes(nonce);
        ControlEnvelope unsigned = new ControlEnvelope(
                1, keyId, requestId, idempotencyKey, Instant.now().toEpochMilli(),
                BASE64.encodeToString(nonce), bridgeId, backend, targetXuid, action,
                BASE64.encodeToString(ControlJson.encode(payload).getBytes(StandardCharsets.UTF_8)), "");
        return unsigned.withSignature(ControlSigner.signRequest(unsigned, secret));
    }

    public ControlEnvelope withSignature(String value) {
        return new ControlEnvelope(version, keyId, requestId, idempotencyKey, timestamp, nonce,
                bridgeId, backend, targetXuid, action, payload, value);
    }

    public String signatureInput() {
        return String.join("\n", "ONICTL/1", keyId, requestId, idempotencyKey,
                Long.toString(timestamp), nonce, bridgeId, backend, targetXuid, action, payload);
    }

    public Map<String, Object> decodedPayload(int maximumCharacters) {
        return ControlJson.parseObject(new String(decode(payload, "payload"), StandardCharsets.UTF_8), maximumCharacters);
    }

    public Map<String, Object> asMap() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("version", version);
        values.put("keyId", keyId);
        values.put("requestId", requestId);
        values.put("idempotencyKey", idempotencyKey);
        values.put("timestamp", timestamp);
        values.put("nonce", nonce);
        values.put("bridgeId", bridgeId);
        values.put("backend", backend);
        values.put("targetXuid", targetXuid);
        values.put("action", action);
        values.put("payload", payload);
        values.put("signature", signature);
        return values;
    }

    public static ControlEnvelope parse(Map<String, Object> values) {
        requireExactFields(values, FIELDS);
        return new ControlEnvelope(
                integer(values, "version"), text(values, "keyId"), text(values, "requestId"),
                text(values, "idempotencyKey"), longValue(values, "timestamp"), text(values, "nonce"),
                text(values, "bridgeId"), text(values, "backend"), text(values, "targetXuid"),
                text(values, "action"), text(values, "payload"), text(values, "signature"));
    }

    static void requireExactFields(Map<String, Object> values, Set<String> fields) {
        if (!values.keySet().equals(fields)) {
            Set<String> unknown = new java.util.LinkedHashSet<>(values.keySet());
            unknown.removeAll(fields);
            Set<String> missing = new java.util.LinkedHashSet<>(fields);
            missing.removeAll(values.keySet());
            throw new IllegalArgumentException("invalid envelope fields; unknown=" + unknown + " missing=" + missing);
        }
    }

    static String text(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (!(value instanceof String text)) throw new IllegalArgumentException(key + " must be a string");
        return text;
    }

    static int integer(Map<String, Object> values, String key) {
        long value = longValue(values, key);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) throw new IllegalArgumentException(key + " is out of range");
        return (int) value;
    }

    static long longValue(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (!(value instanceof Number number)) throw new IllegalArgumentException(key + " must be an integer");
        if (value instanceof Double || value instanceof Float) throw new IllegalArgumentException(key + " must be an integer");
        return number.longValue();
    }

    private static byte[] decode(String value, String label) {
        if (value.indexOf('=') >= 0) throw new IllegalArgumentException(label + " must be unpadded base64url");
        try {
            return BASE64_DECODER.decode(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(label + " must be base64url", exception);
        }
    }

    private static String uuid(String value, String label) {
        String text = required(value, label, 36);
        UUID.fromString(text);
        return text;
    }

    private static String required(String value, String label, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(label + " is missing or invalid");
        }
        return value;
    }
}
