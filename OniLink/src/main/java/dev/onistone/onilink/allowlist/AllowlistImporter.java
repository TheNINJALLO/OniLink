package dev.onistone.onilink.allowlist;

import dev.onistone.onilink.control.ControlJson;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Bounded parser for OniLink JSON/properties exports and simple XUID CSV/text lists. */
public final class AllowlistImporter {
    public static final int MAX_CHARACTERS = 131_072;
    private static final int MAX_ENTRIES = 5_000;
    private static final int MAX_REPORTED_ERRORS = 20;

    private AllowlistImporter() {
    }

    public static ParseResult parse(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Choose a non-empty allowlist file");
        }
        if (content.length() > MAX_CHARACTERS) {
            throw new IllegalArgumentException("Allowlist import exceeds the 128 KiB safety limit");
        }
        String normalized = content.charAt(0) == '\ufeff' ? content.substring(1) : content;
        Collector collector = new Collector();
        String trimmed = normalized.stripLeading();
        if (trimmed.startsWith("[") || trimmed.startsWith("{")) parseJson(trimmed, collector);
        else parseLines(normalized, collector);
        if (collector.entries.isEmpty()) {
            String detail = collector.errors.isEmpty() ? "No XUID entries were found" : collector.errors.getFirst();
            throw new IllegalArgumentException(detail
                    + ". OniLink requires numeric Xbox XUIDs; a name-only BDS allowlist cannot be trusted securely.");
        }
        return new ParseResult(
                List.copyOf(collector.entries.values()),
                collector.rejected,
                List.copyOf(collector.errors));
    }

    private static void parseJson(String input, Collector collector) {
        Map<String, Object> document = input.startsWith("[")
                ? ControlJson.parseObject("{\"entries\":" + input + "}", MAX_CHARACTERS + 16)
                : ControlJson.parseObject(input, MAX_CHARACTERS);
        Object rawEntries = first(document, "entries", "allowlist", "players");
        if (rawEntries instanceof List<?> list) {
            int index = 0;
            for (Object item : list) parseJsonEntry(item, ++index, collector);
            return;
        }
        if (rawEntries != null) {
            collector.reject("JSON entries must be an array");
            return;
        }
        boolean propertyMap = !document.isEmpty() && document.keySet().stream().allMatch(AllowlistImporter::digits);
        if (propertyMap) {
            document.forEach((xuid, label) -> collector.add(xuid, label == null ? "" : String.valueOf(label)));
            return;
        }
        parseJsonEntry(document, 1, collector);
    }

    private static void parseJsonEntry(Object item, int index, Collector collector) {
        if (item instanceof String value) {
            collector.add(value, "");
            return;
        }
        if (!(item instanceof Map<?, ?> map)) {
            collector.reject("JSON entry " + index + " is not an object or XUID string");
            return;
        }
        String xuid = stringField(map, "xuid", "XUID", "id");
        String name = stringField(map, "name", "gamertag", "label");
        if (xuid.isBlank()) {
            collector.reject("JSON entry " + index + (name.isBlank()
                    ? " is missing xuid"
                    : " for " + name + " is name-only and missing xuid"));
            return;
        }
        collector.add(xuid, name);
    }

    private static void parseLines(String input, Collector collector) {
        String[] lines = input.split("\\R", -1);
        int xuidColumn = -1;
        int nameColumn = -1;
        boolean headerChecked = false;
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index].trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) continue;
            List<String> columns = csv(line);
            if (!headerChecked) {
                headerChecked = true;
                for (int column = 0; column < columns.size(); column++) {
                    String heading = columns.get(column).trim().toLowerCase(Locale.ROOT);
                    if (heading.equals("xuid") || heading.equals("xbox_id")) xuidColumn = column;
                    if (heading.equals("name") || heading.equals("gamertag") || heading.equals("label")) {
                        nameColumn = column;
                    }
                }
                if (xuidColumn >= 0) continue;
            }
            if (xuidColumn >= 0) {
                if (xuidColumn >= columns.size()) {
                    collector.reject("Line " + (index + 1) + " is missing its XUID column");
                } else {
                    collector.add(columns.get(xuidColumn),
                            nameColumn >= 0 && nameColumn < columns.size() ? columns.get(nameColumn) : "");
                }
                continue;
            }

            int equals = line.indexOf('=');
            if (equals > 0 && digits(line.substring(0, equals).trim())) {
                collector.add(line.substring(0, equals), line.substring(equals + 1));
            } else if (columns.size() >= 2) {
                int numeric = digits(columns.get(0).trim()) ? 0 : digits(columns.get(1).trim()) ? 1 : -1;
                if (numeric < 0) collector.reject("Line " + (index + 1) + " does not contain a numeric XUID");
                else collector.add(columns.get(numeric), columns.get(numeric == 0 ? 1 : 0));
            } else if (digits(line)) {
                collector.add(line, "");
            } else {
                collector.reject("Line " + (index + 1) + " is name-only or malformed");
            }
        }
    }

    private static List<String> csv(String line) {
        char delimiter = line.indexOf('\t') >= 0 ? '\t' : ',';
        List<String> values = new ArrayList<>();
        StringBuilder value = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            if (character == '"') {
                if (quoted && index + 1 < line.length() && line.charAt(index + 1) == '"') {
                    value.append('"');
                    index++;
                } else quoted = !quoted;
            } else if (character == delimiter && !quoted) {
                values.add(value.toString().trim());
                value.setLength(0);
            } else {
                value.append(character);
            }
        }
        values.add(value.toString().trim());
        return values;
    }

    private static Object first(Map<String, Object> document, String... keys) {
        for (String key : keys) if (document.containsKey(key)) return document.get(key);
        return null;
    }

    private static String stringField(Map<?, ?> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null) return String.valueOf(value).trim();
        }
        return "";
    }

    private static boolean digits(String value) {
        if (value == null || value.isBlank() || value.length() > 32) return false;
        return value.chars().allMatch(character -> character >= '0' && character <= '9');
    }

    public record ParseResult(
            List<ProxyAllowlist.Entry> entries,
            int rejected,
            List<String> errors
    ) {
    }

    private static final class Collector {
        private final Map<String, ProxyAllowlist.Entry> entries = new LinkedHashMap<>();
        private final List<String> errors = new ArrayList<>();
        private int rejected;

        private void add(String rawXuid, String rawName) {
            String xuid = rawXuid == null ? "" : rawXuid.trim();
            String name = rawName == null ? "" : rawName.trim();
            if (!digits(xuid)) {
                reject("Entry has an invalid XUID");
                return;
            }
            if (name.length() > 64 || name.indexOf('\r') >= 0 || name.indexOf('\n') >= 0 || name.indexOf('\0') >= 0) {
                reject("Entry " + xuid + " has an invalid label");
                return;
            }
            if (!entries.containsKey(xuid) && entries.size() >= MAX_ENTRIES) {
                throw new IllegalArgumentException("Allowlist imports are limited to 5,000 unique XUIDs");
            }
            entries.put(xuid, new ProxyAllowlist.Entry(xuid, name));
        }

        private void reject(String message) {
            rejected++;
            if (errors.size() < MAX_REPORTED_ERRORS) errors.add(message);
        }
    }
}
