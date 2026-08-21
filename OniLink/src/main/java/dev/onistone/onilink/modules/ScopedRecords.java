package dev.onistone.onilink.modules;

import dev.onistone.onilink.platform.persistence.PlatformDatabase;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public abstract class ScopedRecords {
    protected final PlatformDatabase database;

    protected ScopedRecords(PlatformDatabase database) {
        this.database = database;
    }

    protected static String id(String value) {
        String id = value == null || value.isBlank() ? UUID.randomUUID().toString() : value.trim();
        if (!id.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException("record ID is invalid");
        }
        return id;
    }

    protected static String required(Map<String, Object> values, String field, int maximum) {
        Object raw = values.get(field);
        String value = raw == null ? "" : String.valueOf(raw).trim();
        if (value.isBlank()) throw new IllegalArgumentException(field + " is required");
        if (value.length() > maximum) throw new IllegalArgumentException(field + " is too long");
        return value;
    }

    protected static long longValue(Object value, long fallback) {
        if (value == null || String.valueOf(value).isBlank()) return fallback;
        if (value instanceof Number number) return number.longValue();
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("numeric value is invalid");
        }
    }

    protected static boolean bool(Object value, boolean fallback) {
        if (value == null) return fallback;
        if (value instanceof Boolean bool) return bool;
        if ("true".equalsIgnoreCase(String.valueOf(value))) return true;
        if ("false".equalsIgnoreCase(String.valueOf(value))) return false;
        throw new IllegalArgumentException("boolean value is invalid");
    }

    protected static Map<String, Object> view(PlatformDatabase.StoredRecord record) {
        Map<String, Object> result = new LinkedHashMap<>(record.value());
        result.put("id", record.id());
        result.put("revision", record.revision());
        result.put("createdAt", record.createdAt().toString());
        result.put("updatedAt", record.updatedAt().toString());
        return Map.copyOf(result);
    }

    protected static List<Map<String, Object>> views(List<PlatformDatabase.StoredRecord> records) {
        return records.stream().map(ScopedRecords::view).toList();
    }
}
