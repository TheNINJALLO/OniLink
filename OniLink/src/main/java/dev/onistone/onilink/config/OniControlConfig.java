package dev.onistone.onilink.config;

import java.net.InetAddress;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

/** Safe, disabled-by-default configuration for OniControl, OniPacket, and OniVirtual. */
public record OniControlConfig(
        Map<String, ControlBackendConfig> backends,
        PacketRulesConfig packetRules,
        VirtualizationConfig virtualization,
        ProtocolLabConfig protocolLab,
        Path dataDirectory
) {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Pattern ENVIRONMENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");

    public OniControlConfig {
        backends = Map.copyOf(backends == null ? Map.of() : backends);
        if (packetRules == null || virtualization == null || protocolLab == null || dataDirectory == null) {
            throw new IllegalArgumentException("OniControl configuration sections cannot be null");
        }
        Set<String> secretSources = new LinkedHashSet<>();
        for (ControlBackendConfig backend : backends.values()) {
            backend.validate();
            if (backend.enabled()) {
                String source = backend.secretEnvironment().isBlank()
                        ? "file:" + backend.secretFile().toAbsolutePath().normalize()
                        : "env:" + backend.secretEnvironment();
                if (!secretSources.add(source)) {
                    throw new IllegalArgumentException("Every enabled OniControl backend requires a unique secret source");
                }
            }
        }
    }

    public boolean enabled() {
        return backends.values().stream().anyMatch(ControlBackendConfig::enabled);
    }

    public ControlBackendConfig backend(String name) {
        if (name == null) return ControlBackendConfig.disabled("");
        return backends.getOrDefault(name.toLowerCase(Locale.ROOT), ControlBackendConfig.disabled(name));
    }

    static OniControlConfig from(Properties properties, Path configDirectory, Set<String> backendNames) {
        Path root = resolve(configDirectory, string(properties, "control.dataDirectory", "dashboard/control"));
        Map<String, ControlBackendConfig> controls = new LinkedHashMap<>();
        String defaultBackend = string(properties, "backend.name", "default");
        for (String backend : backendNames) {
            String scoped = "backend." + backend + ".control.";
            boolean useGlobal = backend.equalsIgnoreCase(defaultBackend);
            boolean enabled = bool(properties, scoped + "enabled",
                    useGlobal && bool(properties, "control.enabled", false));
            String secretFile = choose(properties, scoped + "secretFile", useGlobal ? "control.secretFile" : "", "");
            controls.put(backend.toLowerCase(Locale.ROOT), new ControlBackendConfig(
                    enabled,
                    choose(properties, scoped + "mode", useGlobal ? "control.mode" : "", "advisor"),
                    choose(properties, scoped + "connectHost", useGlobal ? "control.connectHost" : "", "127.0.0.1"),
                    integer(properties, scoped + "connectPort",
                            useGlobal ? integer(properties, "control.connectPort", 19132) : 19132),
                    choose(properties, scoped + "bridgeId", useGlobal ? "control.bridgeId" : "", backend + "-bridge"),
                    choose(properties, scoped + "backendName", useGlobal ? "control.backendName" : "", backend),
                    choose(properties, scoped + "keyId", useGlobal ? "control.keyId" : "", "control-key-1"),
                    choose(properties, scoped + "secretEnvironment",
                            useGlobal ? "control.secretEnvironment" : "", enabled ? environmentName(backend) : ""),
                    secretFile.isBlank() ? Path.of("") : resolve(configDirectory, secretFile),
                    integer(properties, scoped + "connectTimeoutMillis",
                            useGlobal ? integer(properties, "control.connectTimeoutMillis", 5_000) : 5_000),
                    integer(properties, scoped + "requestTimeoutMillis",
                            useGlobal ? integer(properties, "control.requestTimeoutMillis", 10_000) : 10_000),
                    integer(properties, scoped + "maxFrameBytes",
                            useGlobal ? integer(properties, "control.maxFrameBytes", 262_144) : 262_144),
                    integer(properties, scoped + "maxInFlight",
                            useGlobal ? integer(properties, "control.maxInFlight", 32) : 32),
                    integer(properties, scoped + "maxQueued",
                            useGlobal ? integer(properties, "control.maxQueued", 128) : 128),
                    integer(properties, scoped + "maxClockSkewSeconds",
                            useGlobal ? integer(properties, "control.maxClockSkewSeconds", 30) : 30),
                    integer(properties, scoped + "replayRetentionSeconds",
                            useGlobal ? integer(properties, "control.replayRetentionSeconds", 120) : 120),
                    new TlsConfig(
                            bool(properties, scoped + "tls.enabled", useGlobal && bool(properties, "control.tls.enabled", false)),
                            choose(properties, scoped + "tls.serverName", useGlobal ? "control.tls.serverName" : "", ""),
                            path(properties, configDirectory, scoped + "tls.caFile", useGlobal ? "control.tls.caFile" : ""),
                            path(properties, configDirectory, scoped + "tls.clientCertificate",
                                    useGlobal ? "control.tls.clientCertificate" : ""),
                            path(properties, configDirectory, scoped + "tls.clientPrivateKey",
                                    useGlobal ? "control.tls.clientPrivateKey" : ""),
                            choose(properties, scoped + "tls.pinnedCertificateSha256",
                                    useGlobal ? "control.tls.pinnedCertificateSha256" : "", "")
                    ),
                    bool(properties, scoped + "allowInsecurePrivateNetwork",
                            useGlobal && bool(properties, "control.allowInsecurePrivateNetwork", false)),
                    bool(properties, scoped + "allowPublicAddress",
                            useGlobal && bool(properties, "control.allowPublicAddress", false))
            ));
        }
        return new OniControlConfig(
                controls,
                new PacketRulesConfig(
                        bool(properties, "packetRules.enabled", false),
                        integer(properties, "packetRules.maxRules", 500),
                        integer(properties, "packetRules.maxInjectedPacketsPerDecision", 16)
                ),
                new VirtualizationConfig(
                        bool(properties, "virtualization.enabled", false),
                        integer(properties, "virtualization.maxInventorySessions", 100),
                        integer(properties, "virtualization.maxPrivateEntitiesPerPlayer", 256),
                        integer(properties, "virtualization.maxFakeBlocksPerPlayer", 10_000),
                        integer(properties, "virtualization.maxVirtualCommandsPerPlayer", 100)
                ),
                new ProtocolLabConfig(
                        bool(properties, "protocolLab.enabled", false),
                        bool(properties, "protocolLab.allowBackendBound", false),
                        integer(properties, "protocolLab.maxPacketsPerMinute", 30),
                        integer(properties, "protocolLab.maxSessionSeconds", 300),
                        csv(properties.getProperty("protocolLab.allowedXuids", "")),
                        csv(properties.getProperty("protocolLab.allowedBackends", ""))
                ),
                root
        );
    }

    public record ControlBackendConfig(
            boolean enabled,
            String mode,
            String connectHost,
            int connectPort,
            String bridgeId,
            String backendName,
            String keyId,
            String secretEnvironment,
            Path secretFile,
            int connectTimeoutMillis,
            int requestTimeoutMillis,
            int maxFrameBytes,
            int maxInFlight,
            int maxQueued,
            int maxClockSkewSeconds,
            int replayRetentionSeconds,
            TlsConfig tls,
            boolean allowInsecurePrivateNetwork,
            boolean allowPublicAddress
    ) {
        public ControlBackendConfig {
            mode = clean(mode);
            connectHost = clean(connectHost);
            bridgeId = clean(bridgeId);
            backendName = clean(backendName);
            keyId = clean(keyId);
            secretEnvironment = clean(secretEnvironment);
            secretFile = secretFile == null ? Path.of("") : secretFile;
        }

        private void validate() {
            if (!enabled) return;
            if (!mode.equals("advisor") && !mode.equals("enforce")) {
                throw new IllegalArgumentException("OniControl mode must be advisor or enforce");
            }
            if (connectHost.isBlank() || connectPort < 1 || connectPort > 65_535) {
                throw new IllegalArgumentException("Enabled OniControl backend requires a valid control address");
            }
            if (!IDENTIFIER.matcher(bridgeId).matches()
                    || !IDENTIFIER.matcher(backendName).matches()
                    || !IDENTIFIER.matcher(keyId).matches()) {
                throw new IllegalArgumentException("OniControl bridge, backend, and key IDs must be safe identifiers");
            }
            int sources = (!secretEnvironment.isBlank() ? 1 : 0) + (!secretFile.toString().isBlank() ? 1 : 0);
            if (sources != 1 || (!secretEnvironment.isBlank() && !ENVIRONMENT.matcher(secretEnvironment).matches())) {
                throw new IllegalArgumentException("Enabled OniControl backend requires exactly one valid secret source");
            }
            bounded(connectTimeoutMillis, 100, 60_000, "control connect timeout");
            bounded(requestTimeoutMillis, 100, 120_000, "control request timeout");
            bounded(maxFrameBytes, 1_024, 1_048_576, "control frame size");
            bounded(maxInFlight, 1, 1_024, "control in-flight limit");
            bounded(maxQueued, 1, 10_000, "control queue limit");
            bounded(maxClockSkewSeconds, 1, 300, "control clock skew");
            bounded(replayRetentionSeconds, 30, 3_600, "control replay retention");
            tls.validate();
            if (!tls.enabled() && !isLiteralPrivate(connectHost) && !allowPublicAddress) {
                throw new IllegalArgumentException(
                        "Cleartext OniControl requires loopback/private literal host or the dangerous public-address override");
            }
            if (!tls.enabled() && !isLoopback(connectHost) && !allowInsecurePrivateNetwork) {
                throw new IllegalArgumentException(
                        "Cleartext OniControl off loopback requires control.allowInsecurePrivateNetwork=true");
            }
        }

        static ControlBackendConfig disabled(String name) {
            String safe = name == null || name.isBlank() ? "disabled" : name;
            return new ControlBackendConfig(false, "advisor", "127.0.0.1", 19132,
                    safe + "-bridge", safe, "control-key-1", "", Path.of(""),
                    5_000, 10_000, 262_144, 32, 128, 30, 120,
                    TlsConfig.disabled(), false, false);
        }
    }

    public record TlsConfig(
            boolean enabled,
            String serverName,
            Path caFile,
            Path clientCertificate,
            Path clientPrivateKey,
            String pinnedCertificateSha256
    ) {
        public TlsConfig {
            serverName = clean(serverName);
            caFile = caFile == null ? Path.of("") : caFile;
            clientCertificate = clientCertificate == null ? Path.of("") : clientCertificate;
            clientPrivateKey = clientPrivateKey == null ? Path.of("") : clientPrivateKey;
            pinnedCertificateSha256 = clean(pinnedCertificateSha256).replace(":", "").toLowerCase(Locale.ROOT);
        }

        private void validate() {
            if (!enabled) return;
            if (serverName.isBlank()) throw new IllegalArgumentException("TLS server name is required");
            if (caFile.toString().isBlank() && pinnedCertificateSha256.isBlank()) {
                throw new IllegalArgumentException("TLS requires a CA file or certificate pin");
            }
            if (!pinnedCertificateSha256.isBlank()
                    && !pinnedCertificateSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("TLS certificate pin must be a SHA-256 hex digest");
            }
            if (clientCertificate.toString().isBlank() != clientPrivateKey.toString().isBlank()) {
                throw new IllegalArgumentException("TLS client certificate and private key must be configured together");
            }
        }

        static TlsConfig disabled() {
            return new TlsConfig(false, "", Path.of(""), Path.of(""), Path.of(""), "");
        }
    }

    public record PacketRulesConfig(boolean enabled, int maxRules, int maxInjectedPacketsPerDecision) {
        public PacketRulesConfig {
            bounded(maxRules, 1, 10_000, "packet rule limit");
            bounded(maxInjectedPacketsPerDecision, 1, 64, "injected packet limit");
        }
    }

    public record VirtualizationConfig(
            boolean enabled,
            int maxInventorySessions,
            int maxPrivateEntitiesPerPlayer,
            int maxFakeBlocksPerPlayer,
            int maxVirtualCommandsPerPlayer
    ) {
        public VirtualizationConfig {
            bounded(maxInventorySessions, 1, 10_000, "virtual inventory session limit");
            bounded(maxPrivateEntitiesPerPlayer, 1, 10_000, "private entity limit");
            bounded(maxFakeBlocksPerPlayer, 1, 1_000_000, "fake block limit");
            bounded(maxVirtualCommandsPerPlayer, 1, 1_000, "virtual command limit");
        }
    }

    public record ProtocolLabConfig(
            boolean enabled,
            boolean allowBackendBound,
            int maxPacketsPerMinute,
            int maxSessionSeconds,
            Set<String> allowedXuids,
            Set<String> allowedBackends
    ) {
        public ProtocolLabConfig {
            bounded(maxPacketsPerMinute, 1, 600, "Protocol Lab packet rate");
            bounded(maxSessionSeconds, 1, 3_600, "Protocol Lab session duration");
            allowedXuids = Set.copyOf(allowedXuids == null ? Set.of() : allowedXuids);
            allowedBackends = Set.copyOf(allowedBackends == null ? Set.of() : allowedBackends);
            if (enabled && (allowedXuids.isEmpty() || allowedBackends.isEmpty())) {
                throw new IllegalArgumentException("Protocol Lab requires test XUID and backend allowlists");
            }
        }
    }

    private static void bounded(int value, int minimum, int maximum, String label) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(label + " must be " + minimum + ".." + maximum);
        }
    }

    private static String choose(Properties properties, String key, String fallbackKey, String fallback) {
        String value = properties.getProperty(key);
        if (value == null && fallbackKey != null && !fallbackKey.isBlank()) value = properties.getProperty(fallbackKey);
        return value == null ? fallback : ConfigValues.stripInlineComment(value).trim();
    }

    private static String string(Properties properties, String key, String fallback) {
        return choose(properties, key, "", fallback);
    }

    private static boolean bool(Properties properties, String key, boolean fallback) {
        String value = properties.getProperty(key);
        return value == null || value.isBlank() ? fallback : Boolean.parseBoolean(value.trim());
    }

    private static int integer(Properties properties, String key, int fallback) {
        String value = properties.getProperty(key);
        return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
    }

    private static Path path(Properties properties, Path root, String key, String fallbackKey) {
        String value = choose(properties, key, fallbackKey, "");
        return value.isBlank() ? Path.of("") : resolve(root, value);
    }

    private static Path resolve(Path root, String value) {
        Path path = Path.of(value);
        if (!path.isAbsolute() && root != null) path = root.resolve(path);
        return path.toAbsolutePath().normalize();
    }

    private static Set<String> csv(String raw) {
        if (raw == null || raw.isBlank()) return Set.of();
        Set<String> values = new LinkedHashSet<>();
        Arrays.stream(raw.split(",")).map(String::trim).filter(value -> !value.isBlank()).forEach(values::add);
        return Set.copyOf(values);
    }

    private static String environmentName(String backend) {
        return "ONILINK_CONTROL_SECRET_" + backend.replaceAll("[^A-Za-z0-9]", "_").toUpperCase(Locale.ROOT);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean isLoopback(String host) {
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host);
    }

    private static boolean isLiteralPrivate(String host) {
        if (isLoopback(host)) return true;
        try {
            if (!host.matches("[0-9a-fA-F:.]+")) return false;
            InetAddress address = InetAddress.getByName(host);
            return address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress();
        } catch (Exception ignored) {
            return false;
        }
    }
}
