package dev.onistone.onilink.control;

import java.util.List;
import java.util.Map;

/** JSON-compatible, schema-validated semantic payload. */
public record ValidatedActionPayload(int version, Map<String, Object> values) {
    private static final List<String> FORBIDDEN_KEYS = List.of(
            "packetid", "rawbytes", "wirebytes", "stackid", "networkstackid", "memoryaddress",
            "rva", "serializerlayout", "jwt", "token", "signature", "privatekey", "shellcommand");

    public ValidatedActionPayload {
        if (version != 1) throw new IllegalArgumentException("unsupported action payload version");
        values = ControlValues.immutableObject(values);
        rejectForbidden(values, "payload");
    }

    private static void rejectForbidden(Map<String, Object> values, String path) {
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String normalized = entry.getKey().replaceAll("[^A-Za-z0-9]", "").toLowerCase();
            if (FORBIDDEN_KEYS.contains(normalized)) {
                throw new IllegalArgumentException(path + " contains forbidden field " + entry.getKey());
            }
            if (entry.getValue() instanceof Map<?, ?> nested) {
                @SuppressWarnings("unchecked") Map<String, Object> object = (Map<String, Object>) nested;
                rejectForbidden(object, path + "." + entry.getKey());
            } else if (entry.getValue() instanceof List<?> list) {
                for (int index = 0; index < list.size(); index++) {
                    if (list.get(index) instanceof Map<?, ?> nested) {
                        @SuppressWarnings("unchecked") Map<String, Object> object = (Map<String, Object>) nested;
                        rejectForbidden(object, path + "." + entry.getKey() + '[' + index + ']');
                    }
                }
            }
        }
    }
}
