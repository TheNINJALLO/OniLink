package dev.onistone.onilink.control.wire;

import dev.onistone.onilink.control.ControlJson;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public record ControlResponseEnvelope(
        int version,
        String keyId,
        String requestId,
        String status,
        long timestamp,
        String bridgeId,
        String backend,
        long capabilityRevision,
        String payload,
        String signature
) {
    private static final Set<String> FIELDS = Set.of(
            "version", "keyId", "requestId", "status", "timestamp", "bridgeId", "backend",
            "capabilityRevision", "payload", "signature");

    public ControlResponseEnvelope {
        if (version != 1 || keyId == null || requestId == null || status == null || bridgeId == null
                || backend == null || payload == null || signature == null || capabilityRevision < 0) {
            throw new IllegalArgumentException("invalid ONICTL response envelope");
        }
    }

    public String signatureInput() {
        return String.join("\n", "ONICTL/1-RESPONSE", keyId, requestId, status,
                Long.toString(timestamp), bridgeId, backend, Long.toString(capabilityRevision), payload);
    }

    public Map<String, Object> decodedPayload(int maximumCharacters) {
        if (payload.indexOf('=') >= 0) throw new IllegalArgumentException("response payload must be unpadded base64url");
        byte[] decoded = Base64.getUrlDecoder().decode(payload);
        return ControlJson.parseObject(new String(decoded, StandardCharsets.UTF_8), maximumCharacters);
    }

    public Map<String, Object> asMap() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("version", version);
        values.put("keyId", keyId);
        values.put("requestId", requestId);
        values.put("status", status);
        values.put("timestamp", timestamp);
        values.put("bridgeId", bridgeId);
        values.put("backend", backend);
        values.put("capabilityRevision", capabilityRevision);
        values.put("payload", payload);
        values.put("signature", signature);
        return values;
    }

    public static ControlResponseEnvelope parse(Map<String, Object> values) {
        ControlEnvelope.requireExactFields(values, FIELDS);
        return new ControlResponseEnvelope(
                ControlEnvelope.integer(values, "version"), ControlEnvelope.text(values, "keyId"),
                ControlEnvelope.text(values, "requestId"), ControlEnvelope.text(values, "status"),
                ControlEnvelope.longValue(values, "timestamp"), ControlEnvelope.text(values, "bridgeId"),
                ControlEnvelope.text(values, "backend"), ControlEnvelope.longValue(values, "capabilityRevision"),
                ControlEnvelope.text(values, "payload"), ControlEnvelope.text(values, "signature"));
    }
}
