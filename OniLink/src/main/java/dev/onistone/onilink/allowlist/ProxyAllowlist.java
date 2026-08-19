package dev.onistone.onilink.allowlist;

import dev.onistone.onilink.config.AllowlistConfig;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * Durable XUID allowlist enforced after Xbox authentication and before backend connection.
 *
 * <p>Gamertags are retained only as operator-facing labels. Authorization always uses the XUID,
 * because names can change ownership while an Xbox account XUID is stable.</p>
 */
public final class ProxyAllowlist {
    private static final int MAX_XUID_LENGTH = 32;
    private static final int MAX_LABEL_LENGTH = 64;

    private final AllowlistConfig config;
    private final boolean persistent;
    private final Map<String, String> entries = new TreeMap<>();

    private ProxyAllowlist(AllowlistConfig config, boolean persistent) {
        this.config = config == null ? AllowlistConfig.defaults() : config;
        this.persistent = persistent;
    }

    /** Loads the configured file. A malformed or unreadable existing file is a startup failure. */
    public static ProxyAllowlist load(AllowlistConfig config) throws IOException {
        ProxyAllowlist allowlist = new ProxyAllowlist(config, true);
        Path file = allowlist.config.file();
        if (Files.notExists(file)) {
            if (allowlist.enabled()) {
                System.out.printf("Allowlist enabled with no existing %s; all players are denied until an XUID is added.%n",
                        file);
            }
            return allowlist;
        }
        if (!Files.isRegularFile(file)) {
            throw new IOException("Allowlist path is not a regular file: " + file);
        }
        Properties properties = new Properties();
        try (Reader input = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(input);
        }
        for (String rawXuid : properties.stringPropertyNames()) {
            String xuid = requireXuid(rawXuid);
            String label = cleanLabel(properties.getProperty(rawXuid));
            allowlist.entries.put(xuid, label);
        }
        System.out.printf("Loaded %d allow-listed XUID(s) from %s; enforcement is %s.%n",
                allowlist.entries.size(), file, allowlist.enabled() ? "ON" : "off");
        return allowlist;
    }

    /** In-memory form used by unit tests and listener constructors that do not own a config path. */
    public static ProxyAllowlist inMemory(AllowlistConfig config) {
        return new ProxyAllowlist(config, false);
    }

    public static ProxyAllowlist disabled() {
        return inMemory(AllowlistConfig.defaults());
    }

    public boolean enabled() {
        return config.enabled();
    }

    public AllowlistConfig config() {
        return config;
    }

    public synchronized boolean allows(String xuid) {
        return !enabled() || entries.containsKey(normalizeXuid(xuid));
    }

    public synchronized boolean contains(String xuid) {
        return entries.containsKey(normalizeXuid(xuid));
    }

    public synchronized List<Entry> entries() {
        List<Entry> copy = new ArrayList<>();
        entries.forEach((xuid, name) -> copy.add(new Entry(xuid, name)));
        return List.copyOf(copy);
    }

    public synchronized String xuidForLabel(String label) {
        if (label == null || label.isBlank()) return "";
        String wanted = label.trim();
        return entries.entrySet().stream()
                .filter(entry -> entry.getValue().equalsIgnoreCase(wanted))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse("");
    }

    /** Adds an XUID or refreshes its display label. */
    public synchronized boolean add(String xuid, String label) throws IOException {
        String key = requireXuid(xuid);
        String value = cleanLabel(label);
        String previous = entries.put(key, value);
        if (value.equals(previous)) return false;
        try {
            save();
        } catch (IOException exception) {
            restore(key, previous);
            throw exception;
        }
        return true;
    }

    public synchronized boolean remove(String xuid) throws IOException {
        String key = requireXuid(xuid);
        String previous = entries.remove(key);
        if (previous == null) return false;
        try {
            save();
        } catch (IOException exception) {
            entries.put(key, previous);
            throw exception;
        }
        return true;
    }

    private void restore(String key, String previous) {
        if (previous == null) entries.remove(key);
        else entries.put(key, previous);
    }

    private void save() throws IOException {
        if (!persistent) return;
        Path file = config.file();
        Path parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Properties properties = new Properties();
        entries.forEach(properties::setProperty);
        try {
            try (Writer output = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                properties.store(output, "OniLink proxy allowlist. Keys are authenticated Xbox XUIDs; values are labels only.");
            }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String requireXuid(String value) {
        String xuid = normalizeXuid(value);
        if (xuid.isEmpty() || xuid.length() > MAX_XUID_LENGTH
                || !xuid.chars().allMatch(character -> character >= '0' && character <= '9')) {
            throw new IllegalArgumentException("XUID must contain 1-" + MAX_XUID_LENGTH + " ASCII digits");
        }
        return xuid;
    }

    private static String normalizeXuid(String value) {
        return value == null ? "" : value.trim();
    }

    private static String cleanLabel(String value) {
        String label = value == null ? "" : value.trim();
        if (label.length() > MAX_LABEL_LENGTH || label.indexOf('\0') >= 0
                || label.indexOf('\r') >= 0 || label.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("Allowlist label must be one line of at most "
                    + MAX_LABEL_LENGTH + " characters");
        }
        return label;
    }

    public record Entry(String xuid, String name) {
    }
}
