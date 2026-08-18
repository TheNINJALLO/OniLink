package dev.onistone.onilink.geyser;

/** Small strict JSON reader used only to recover one top-level string from Geyser's preserved client data. */
final class TopLevelJson {
    private static final int MAX_DEPTH = 64;

    private final String source;
    private int offset;

    private TopLevelJson(String source) {
        this.source = source;
    }

    static String uniqueString(String json, String wantedKey) {
        if (json == null || json.length() > 2_000_000) {
            throw new IllegalArgumentException("client data JSON size is invalid");
        }
        TopLevelJson parser = new TopLevelJson(json);
        parser.whitespace();
        parser.expect('{');
        parser.whitespace();
        String result = null;
        if (parser.consume('}')) {
            throw new IllegalArgumentException("required client data claim is missing");
        }
        while (true) {
            String key = parser.string();
            parser.whitespace();
            parser.expect(':');
            parser.whitespace();
            if (key.equals(wantedKey)) {
                if (result != null) {
                    throw new IllegalArgumentException("required client data claim is duplicated");
                }
                result = parser.string();
            } else {
                parser.value(1);
            }
            parser.whitespace();
            if (parser.consume('}')) {
                break;
            }
            parser.expect(',');
            parser.whitespace();
        }
        parser.whitespace();
        if (parser.offset != parser.source.length()) {
            throw new IllegalArgumentException("trailing client data JSON");
        }
        if (result == null || result.isEmpty()) {
            throw new IllegalArgumentException("required client data claim is missing");
        }
        return result;
    }

    private void value(int depth) {
        if (depth > MAX_DEPTH || offset >= source.length()) {
            throw new IllegalArgumentException("client data JSON nesting is invalid");
        }
        char next = source.charAt(offset);
        if (next == '"') {
            string();
        } else if (next == '{') {
            object(depth + 1);
        } else if (next == '[') {
            array(depth + 1);
        } else if (next == 't') {
            literal("true");
        } else if (next == 'f') {
            literal("false");
        } else if (next == 'n') {
            literal("null");
        } else {
            number();
        }
    }

    private void object(int depth) {
        expect('{');
        whitespace();
        if (consume('}')) {
            return;
        }
        while (true) {
            string();
            whitespace();
            expect(':');
            whitespace();
            value(depth);
            whitespace();
            if (consume('}')) {
                return;
            }
            expect(',');
            whitespace();
        }
    }

    private void array(int depth) {
        expect('[');
        whitespace();
        if (consume(']')) {
            return;
        }
        while (true) {
            value(depth);
            whitespace();
            if (consume(']')) {
                return;
            }
            expect(',');
            whitespace();
        }
    }

    private String string() {
        expect('"');
        StringBuilder result = new StringBuilder();
        while (offset < source.length()) {
            char character = source.charAt(offset++);
            if (character == '"') {
                return result.toString();
            }
            if (character < 0x20) {
                throw new IllegalArgumentException("control character in client data JSON string");
            }
            if (character != '\\') {
                result.append(character);
                continue;
            }
            if (offset >= source.length()) {
                throw new IllegalArgumentException("truncated client data JSON escape");
            }
            char escaped = source.charAt(offset++);
            switch (escaped) {
                case '"', '\\', '/' -> result.append(escaped);
                case 'b' -> result.append('\b');
                case 'f' -> result.append('\f');
                case 'n' -> result.append('\n');
                case 'r' -> result.append('\r');
                case 't' -> result.append('\t');
                case 'u' -> result.append(unicode());
                default -> throw new IllegalArgumentException("invalid client data JSON escape");
            }
        }
        throw new IllegalArgumentException("unterminated client data JSON string");
    }

    private char unicode() {
        if (offset + 4 > source.length()) {
            throw new IllegalArgumentException("truncated client data JSON unicode escape");
        }
        int value = 0;
        for (int index = 0; index < 4; index++) {
            int digit = Character.digit(source.charAt(offset++), 16);
            if (digit < 0) {
                throw new IllegalArgumentException("invalid client data JSON unicode escape");
            }
            value = value << 4 | digit;
        }
        return (char) value;
    }

    private void number() {
        int start = offset;
        if (consume('-')) {
            // sign consumed
        }
        if (consume('0')) {
            // zero cannot have another integer digit
        } else {
            digits();
        }
        if (consume('.')) {
            digits();
        }
        if (consume('e') || consume('E')) {
            if (!consume('+')) {
                consume('-');
            }
            digits();
        }
        if (offset == start || offset == start + 1 && source.charAt(start) == '-') {
            throw new IllegalArgumentException("invalid client data JSON value");
        }
    }

    private void digits() {
        int start = offset;
        while (offset < source.length() && Character.isDigit(source.charAt(offset))) {
            offset++;
        }
        if (start == offset) {
            throw new IllegalArgumentException("invalid client data JSON number");
        }
    }

    private void literal(String literal) {
        if (!source.startsWith(literal, offset)) {
            throw new IllegalArgumentException("invalid client data JSON literal");
        }
        offset += literal.length();
    }

    private void whitespace() {
        while (offset < source.length()) {
            char character = source.charAt(offset);
            if (character != ' ' && character != '\n' && character != '\r' && character != '\t') {
                return;
            }
            offset++;
        }
    }

    private boolean consume(char expected) {
        if (offset < source.length() && source.charAt(offset) == expected) {
            offset++;
            return true;
        }
        return false;
    }

    private void expect(char expected) {
        if (!consume(expected)) {
            throw new IllegalArgumentException("invalid client data JSON");
        }
    }
}
