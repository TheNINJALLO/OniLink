package dev.onistone.onilink.backend;

import dev.onistone.onilink.config.BackendConfig;
import dev.onistone.onilink.modules.fleet.RoutingOverrides;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class BackendDirectory {
    private volatile Map<String, BackendConfig> backends;
    private final Set<String> staticBackends;
    private final Set<String> disabled = new LinkedHashSet<>();
    private final Set<String> draining = new LinkedHashSet<>();
    private long revision = 1;
    private final String defaultBackendName;
    private final String hubBackendName;

    public BackendDirectory(Map<String, BackendConfig> backends, String defaultBackendName, String hubBackendName) {
        if (backends == null || backends.isEmpty()) {
            throw new IllegalArgumentException("backends cannot be empty");
        }
        this.backends = normalized(backends);
        this.staticBackends = Set.copyOf(this.backends.keySet());
        this.defaultBackendName = normalize(defaultBackendName);
        this.hubBackendName = normalize(hubBackendName);
        if (!this.backends.containsKey(this.defaultBackendName)) {
            throw new IllegalArgumentException("default backend is not configured: " + defaultBackendName);
        }
        if (!this.backends.containsKey(this.hubBackendName)) {
            throw new IllegalArgumentException("hub backend is not configured: " + hubBackendName);
        }
    }

    public BackendConfig defaultBackend() {
        BackendConfig configured = find(defaultBackendName).orElse(null);
        if (configured != null) return configured;
        return backends.entrySet().stream()
                .filter(entry -> routable(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No active backend is available for new joins"));
    }

    public BackendConfig hubBackend() {
        return find(hubBackendName).orElseGet(this::defaultBackend);
    }

    public Optional<BackendConfig> find(String name) {
        String key = normalize(name);
        return routable(key) ? Optional.ofNullable(backends.get(key)) : Optional.empty();
    }

    /** Management lookup that includes disabled and draining backends. */
    public Optional<BackendConfig> findAny(String name) {
        return Optional.ofNullable(backends.get(normalize(name)));
    }

    /** Explicit operation lookup, including limbo/quarantine backends but excluding unavailable ones. */
    public synchronized Optional<BackendConfig> findOperational(String name) {
        String key = normalize(name);
        return backends.containsKey(key) && !disabled.contains(key) && !draining.contains(key)
                ? Optional.of(backends.get(key)) : Optional.empty();
    }

    public synchronized long registerDynamic(BackendConfig backend, long expectedRevision) {
        String key = normalize(backend.name());
        if (backends.containsKey(key)) throw new IllegalArgumentException("backend already exists: " + backend.name());
        checkRevision(expectedRevision);
        Map<String, BackendConfig> next = new LinkedHashMap<>(backends);
        next.put(key, backend);
        backends = Map.copyOf(next);
        return ++revision;
    }

    public synchronized long updateDynamic(BackendConfig backend, long expectedRevision) {
        String key = normalize(backend.name());
        if (!backends.containsKey(key)) throw new IllegalArgumentException("unknown backend: " + backend.name());
        if (staticBackends.contains(key)) throw new IllegalArgumentException("static backends must be changed in config.properties");
        checkRevision(expectedRevision);
        Map<String, BackendConfig> next = new LinkedHashMap<>(backends);
        next.put(key, backend);
        backends = Map.copyOf(next);
        return ++revision;
    }

    public synchronized long removeDynamic(String name, long expectedRevision) {
        String key = normalize(name);
        if (staticBackends.contains(key)) throw new IllegalArgumentException("static backends cannot be removed at runtime");
        if (!backends.containsKey(key)) throw new IllegalArgumentException("unknown backend: " + name);
        checkRevision(expectedRevision);
        Map<String, BackendConfig> next = new LinkedHashMap<>(backends);
        next.remove(key);
        backends = Map.copyOf(next);
        disabled.remove(key);
        draining.remove(key);
        return ++revision;
    }

    public synchronized long setEnabled(String name, boolean enabled, long expectedRevision) {
        String key = normalize(name);
        if (!backends.containsKey(key)) throw new IllegalArgumentException("unknown backend: " + name);
        checkRevision(expectedRevision);
        boolean changed = enabled ? disabled.remove(key) : disabled.add(key);
        if (changed) revision++;
        return revision;
    }

    public synchronized long setDraining(String name, boolean value, long expectedRevision) {
        String key = normalize(name);
        if (!backends.containsKey(key)) throw new IllegalArgumentException("unknown backend: " + name);
        checkRevision(expectedRevision);
        boolean changed = value ? draining.add(key) : draining.remove(key);
        if (changed) revision++;
        return revision;
    }

    public synchronized boolean isDraining(String name) {
        return draining.contains(normalize(name));
    }

    public synchronized boolean isEnabled(String name) {
        return !disabled.contains(normalize(name));
    }

    public synchronized long revision() {
        return revision;
    }

    public synchronized Map<String, Object> status(String name) {
        String key = normalize(name);
        return Map.of("enabled", !disabled.contains(key), "draining", draining.contains(key),
                "reserved", RoutingOverrides.reservedBackend(key),
                "dynamic", !staticBackends.contains(key), "revision", revision());
    }

    private void checkRevision(long expectedRevision) {
        if (expectedRevision != revision()) {
            throw new IllegalStateException("backend registry revision conflict: expected "
                    + expectedRevision + " but current revision is " + revision());
        }
    }

    private synchronized boolean routable(String key) {
        return backends.containsKey(key) && !disabled.contains(key) && !draining.contains(key)
                && !RoutingOverrides.reservedBackend(key);
    }

    /**
     * Finds the configured backend addressed by a Bedrock {@code TransferPacket}.
     *
     * <p>Only endpoints already present in the proxy configuration qualify. Hostnames are matched
     * case-insensitively (and without a trailing DNS dot), while a backend whose configured address
     * was resolved at startup may also be addressed by its numeric IP. Resolving an arbitrary host
     * supplied by a backend here would block the Netty packet thread, so aliases which are neither
     * the configured hostname nor its resolved numeric address deliberately fall through to the
     * normal client-side transfer.</p>
     */
    public Optional<BackendConfig> findByAddress(String host, int port) {
        if (host == null || host.isBlank() || port < 1 || port > 65_535) {
            return Optional.empty();
        }
        String normalizedHost = normalizeHost(host);
        return backends.values().stream()
                .filter(backend -> routable(normalize(backend.name())))
                .filter(backend -> backend.address().getPort() == port)
                .filter(backend -> matchesHost(backend.address(), normalizedHost))
                .findFirst();
    }

    public Collection<BackendConfig> backends() {
        return backends.values();
    }

    public Collection<BackendConfig> routableBackends() {
        return backends.entrySet().stream().filter(entry -> routable(entry.getKey()))
                .map(Map.Entry::getValue).toList();
    }

    public Collection<String> backendNames() {
        return backends.values().stream()
                .map(BackendConfig::name)
                .toList();
    }

    public Collection<String> routableBackendNames() {
        return routableBackends().stream().map(BackendConfig::name).toList();
    }

    private static Map<String, BackendConfig> normalized(Map<String, BackendConfig> input) {
        Map<String, BackendConfig> result = new LinkedHashMap<>();
        for (BackendConfig backend : input.values()) {
            result.put(normalize(backend.name()), backend);
        }
        return Map.copyOf(result);
    }

    private static String normalize(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("backend name cannot be blank");
        }
        return name.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean matchesHost(InetSocketAddress address, String transferHost) {
        if (normalizeHost(address.getHostString()).equals(transferHost)) {
            return true;
        }
        InetAddress resolved = address.getAddress();
        return resolved != null && normalizeHost(resolved.getHostAddress()).equals(transferHost);
    }

    private static String normalizeHost(String host) {
        String normalized = host.trim();
        if (normalized.length() > 1 && normalized.charAt(0) == '['
                && normalized.charAt(normalized.length() - 1) == ']') {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        while (normalized.endsWith(".") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.toLowerCase(Locale.ROOT);
    }
}
