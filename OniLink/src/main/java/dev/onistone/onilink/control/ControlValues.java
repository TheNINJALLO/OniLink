package dev.onistone.onilink.control;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ControlValues {
    private ControlValues() {
    }

    static Map<String, Object> immutableObject(Map<String, Object> source) {
        if (source == null) return Map.of();
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank() || copy.containsKey(key)) {
                throw new IllegalArgumentException("payload keys must be unique and non-empty");
            }
            copy.put(key, immutableValue(entry.getValue(), 0));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Object immutableValue(Object value, int depth) {
        if (depth > 12) throw new IllegalArgumentException("payload nesting exceeds 12 levels");
        if (value == null || value instanceof String || value instanceof Boolean
                || value instanceof Integer || value instanceof Long) return value;
        if (value instanceof Float number) {
            if (!Float.isFinite(number)) throw new IllegalArgumentException("payload number must be finite");
            return number.doubleValue();
        }
        if (value instanceof Double number) {
            if (!Double.isFinite(number)) throw new IllegalArgumentException("payload number must be finite");
            return number;
        }
        if (value instanceof Number number) return number.longValue();
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> nested = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException("payload object keys must be strings");
                }
                nested.put(key, immutableValue(entry.getValue(), depth + 1));
            }
            return Collections.unmodifiableMap(nested);
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> nested = new ArrayList<>();
            for (Object item : iterable) nested.add(immutableValue(item, depth + 1));
            return Collections.unmodifiableList(nested);
        }
        throw new IllegalArgumentException("payload contains unsupported value type " + value.getClass().getName());
    }
}
