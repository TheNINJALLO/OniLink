package dev.onistone.onilink.control;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small strict JSON codec used by ONICTL/1 and bounded control persistence. */
public final class ControlJson {
    private static final int MAX_DEPTH = 32;

    private ControlJson() {
    }

    public static Map<String, Object> parseObject(String input, int maximumCharacters) {
        if (input == null) throw new IllegalArgumentException("JSON input is required");
        if (input.length() > maximumCharacters) throw new IllegalArgumentException("JSON input exceeds the configured limit");
        Parser parser = new Parser(input);
        Object value = parser.value(0);
        parser.whitespace();
        if (!parser.end()) throw new IllegalArgumentException("unexpected trailing JSON data at character " + parser.index);
        if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException("JSON root must be an object");
        @SuppressWarnings("unchecked") Map<String, Object> object = (Map<String, Object>) map;
        return object;
    }

    public static String encode(Object value) {
        StringBuilder output = new StringBuilder(256);
        append(output, value, 0);
        return output.toString();
    }

    private static void append(StringBuilder output, Object value, int depth) {
        if (depth > MAX_DEPTH) throw new IllegalArgumentException("JSON nesting exceeds " + MAX_DEPTH + " levels");
        if (value == null) {
            output.append("null");
        } else if (value instanceof String string) {
            string(output, string);
        } else if (value instanceof Double number) {
            if (!Double.isFinite(number)) throw new IllegalArgumentException("JSON number must be finite");
            output.append(number);
        } else if (value instanceof Float number) {
            if (!Float.isFinite(number)) throw new IllegalArgumentException("JSON number must be finite");
            output.append(number);
        } else if (value instanceof Number || value instanceof Boolean) {
            output.append(value);
        } else if (value instanceof Enum<?> enumeration) {
            string(output, enumeration.name());
        } else if (value instanceof Map<?, ?> map) {
            output.append('{');
            Iterator<? extends Map.Entry<?, ?>> iterator = map.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<?, ?> entry = iterator.next();
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException("JSON object key must be a string");
                }
                string(output, key);
                output.append(':');
                append(output, entry.getValue(), depth + 1);
                if (iterator.hasNext()) output.append(',');
            }
            output.append('}');
        } else if (value instanceof Iterable<?> iterable) {
            output.append('[');
            Iterator<?> iterator = iterable.iterator();
            while (iterator.hasNext()) {
                append(output, iterator.next(), depth + 1);
                if (iterator.hasNext()) output.append(',');
            }
            output.append(']');
        } else if (value.getClass().isArray()) {
            output.append('[');
            for (int index = 0; index < Array.getLength(value); index++) {
                if (index > 0) output.append(',');
                append(output, Array.get(value, index), depth + 1);
            }
            output.append(']');
        } else {
            throw new IllegalArgumentException("unsupported JSON value type " + value.getClass().getName());
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
                    if (character < 0x20 || Character.isSurrogate(character)) {
                        output.append(String.format("\\u%04x", (int) character));
                    } else {
                        output.append(character);
                    }
                }
            }
        }
        output.append('"');
    }

    private static final class Parser {
        private final String input;
        private int index;

        private Parser(String input) {
            this.input = input;
        }

        private Object value(int depth) {
            if (depth > MAX_DEPTH) throw error("JSON nesting exceeds " + MAX_DEPTH + " levels");
            whitespace();
            if (end()) throw error("unexpected end of JSON");
            return switch (input.charAt(index)) {
                case '{' -> object(depth + 1);
                case '[' -> array(depth + 1);
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        private Map<String, Object> object(int depth) {
            index++;
            whitespace();
            Map<String, Object> values = new LinkedHashMap<>();
            if (take('}')) return Map.of();
            while (true) {
                whitespace();
                if (end() || input.charAt(index) != '"') throw error("object key must be a string");
                String key = string();
                whitespace();
                if (!take(':')) throw error("missing colon after object key");
                if (values.containsKey(key)) throw error("duplicate object key " + key);
                values.put(key, value(depth));
                whitespace();
                if (take('}')) return Collections.unmodifiableMap(values);
                if (!take(',')) throw error("missing comma between object members");
            }
        }

        private List<Object> array(int depth) {
            index++;
            whitespace();
            List<Object> values = new ArrayList<>();
            if (take(']')) return List.of();
            while (true) {
                values.add(value(depth));
                whitespace();
                if (take(']')) return Collections.unmodifiableList(values);
                if (!take(',')) throw error("missing comma between array values");
            }
        }

        private String string() {
            index++;
            StringBuilder value = new StringBuilder();
            while (!end()) {
                char character = input.charAt(index++);
                if (character == '"') return value.toString();
                if (character < 0x20) throw error("unescaped control character in string");
                if (character != '\\') {
                    if (Character.isSurrogate(character)) throw error("unpaired surrogate in string");
                    value.append(character);
                    continue;
                }
                if (end()) throw error("unfinished string escape");
                char escape = input.charAt(index++);
                switch (escape) {
                    case '"', '\\', '/' -> value.append(escape);
                    case 'b' -> value.append('\b');
                    case 'f' -> value.append('\f');
                    case 'n' -> value.append('\n');
                    case 'r' -> value.append('\r');
                    case 't' -> value.append('\t');
                    case 'u' -> {
                        char decoded = unicode();
                        if (Character.isHighSurrogate(decoded)) {
                            if (index + 1 >= input.length() || input.charAt(index) != '\\' || input.charAt(index + 1) != 'u') {
                                throw error("high surrogate is not followed by a low surrogate");
                            }
                            index += 2;
                            char low = unicode();
                            if (!Character.isLowSurrogate(low)) throw error("invalid low surrogate");
                            value.append(decoded).append(low);
                        } else if (Character.isLowSurrogate(decoded)) {
                            throw error("unpaired low surrogate");
                        } else {
                            value.append(decoded);
                        }
                    }
                    default -> throw error("unsupported string escape");
                }
            }
            throw error("unterminated string");
        }

        private char unicode() {
            if (index + 4 > input.length()) throw error("unfinished unicode escape");
            int value = 0;
            for (int count = 0; count < 4; count++) {
                int digit = Character.digit(input.charAt(index++), 16);
                if (digit < 0) throw error("invalid unicode escape");
                value = (value << 4) | digit;
            }
            return (char) value;
        }

        private Object number() {
            int start = index;
            take('-');
            if (take('0')) {
                if (!end() && Character.isDigit(input.charAt(index))) throw error("leading zero in number");
            } else {
                if (end() || !Character.isDigit(input.charAt(index))) throw error("invalid JSON value");
                while (!end() && Character.isDigit(input.charAt(index))) index++;
            }
            boolean decimal = false;
            if (take('.')) {
                decimal = true;
                if (end() || !Character.isDigit(input.charAt(index))) throw error("fraction requires digits");
                while (!end() && Character.isDigit(input.charAt(index))) index++;
            }
            if (!end() && (input.charAt(index) == 'e' || input.charAt(index) == 'E')) {
                decimal = true;
                index++;
                if (!end() && (input.charAt(index) == '+' || input.charAt(index) == '-')) index++;
                if (end() || !Character.isDigit(input.charAt(index))) throw error("exponent requires digits");
                while (!end() && Character.isDigit(input.charAt(index))) index++;
            }
            String raw = input.substring(start, index);
            try {
                if (!decimal) return Long.parseLong(raw);
                double number = Double.parseDouble(raw);
                if (!Double.isFinite(number)) throw error("number is not finite");
                return number;
            } catch (NumberFormatException exception) {
                throw error("number is outside supported range");
            }
        }

        private Object literal(String text, Object value) {
            if (!input.startsWith(text, index)) throw error("invalid JSON literal");
            index += text.length();
            return value;
        }

        private boolean take(char expected) {
            if (!end() && input.charAt(index) == expected) {
                index++;
                return true;
            }
            return false;
        }

        private void whitespace() {
            while (!end()) {
                char character = input.charAt(index);
                if (character != ' ' && character != '\t' && character != '\r' && character != '\n') return;
                index++;
            }
        }

        private boolean end() {
            return index >= input.length();
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at character " + index);
        }
    }
}
