package dev.onistone.onilink.dashboard;

import java.lang.reflect.Array;
import java.util.Iterator;
import java.util.Map;

/** Small dependency-free JSON encoder for the dashboard's bounded response objects. */
final class DashboardJson {
    private DashboardJson() {
    }

    static String encode(Object value) {
        StringBuilder output = new StringBuilder(256);
        append(output, value);
        return output.toString();
    }

    private static void append(StringBuilder output, Object value) {
        if (value == null) {
            output.append("null");
        } else if (value instanceof String string) {
            string(output, string);
        } else if (value instanceof Double number && !Double.isFinite(number)) {
            output.append("null");
        } else if (value instanceof Float number && !Float.isFinite(number)) {
            output.append("null");
        } else if (value instanceof Number || value instanceof Boolean) {
            output.append(value);
        } else if (value instanceof Map<?, ?> map) {
            output.append('{');
            Iterator<? extends Map.Entry<?, ?>> iterator = map.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<?, ?> entry = iterator.next();
                string(output, String.valueOf(entry.getKey()));
                output.append(':');
                append(output, entry.getValue());
                if (iterator.hasNext()) output.append(',');
            }
            output.append('}');
        } else if (value instanceof Iterable<?> iterable) {
            output.append('[');
            Iterator<?> iterator = iterable.iterator();
            while (iterator.hasNext()) {
                append(output, iterator.next());
                if (iterator.hasNext()) output.append(',');
            }
            output.append(']');
        } else if (value.getClass().isArray()) {
            output.append('[');
            int length = Array.getLength(value);
            for (int index = 0; index < length; index++) {
                if (index > 0) output.append(',');
                append(output, Array.get(value, index));
            }
            output.append(']');
        } else {
            string(output, String.valueOf(value));
        }
    }

    private static void string(StringBuilder output, String value) {
        output.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> output.append("\\\"");
                case '\\' -> output.append("\\\\");
                case '\b' -> output.append("\\b");
                case '\f' -> output.append("\\f");
                case '\n' -> output.append("\\n");
                case '\r' -> output.append("\\r");
                case '\t' -> output.append("\\t");
                default -> {
                    if (character < 0x20) {
                        output.append(String.format("\\u%04x", (int) character));
                    } else {
                        output.append(character);
                    }
                }
            }
        }
        output.append('"');
    }
}
