package dev.onistone.onilink.config;

import org.cloudburstmc.protocol.bedrock.data.PacketCompressionAlgorithm;
import dev.onistone.onilink.protocol.CanonicalProtocol;
import org.junit.jupiter.api.Test;

import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProxyConfigTest {
    @Test
    void loadsDefaults() {
        ProxyConfig config = ProxyConfig.from(new Properties());

        assertEquals("0.0.0.0", config.listenAddress().getHostString());
        assertEquals(19132, config.listenAddress().getPort());
        assertEquals("127.0.0.1", config.backend().address().getHostString());
        assertEquals(19133, config.backend().address().getPort());
        assertEquals("default", config.hubBackendName());
        assertNull(config.backendProtocol());
        assertEquals(1, config.backends().size());
        assertEquals(19133, config.backends().get("default").address().getPort());
        assertEquals(false, config.legacyVerification().enabled());
        assertEquals("127.0.0.1", config.legacyVerification().listenAddress().getHostString());
        assertEquals(19135, config.legacyVerification().listenAddress().getPort());
        assertEquals("OniLink", config.motd());
        assertEquals(PacketCompressionAlgorithm.ZLIB, config.compressionAlgorithm());
        assertFalse(config.allowlist().enabled());
        assertEquals("allowlist.properties", config.allowlist().file().getFileName().toString());
        assertTrue(config.allowlist().disconnectOnRemoval());
    }

    /**
     * The shipped example must actually load, and every key in it must be one the proxy reads.
     *
     * <p>It is documentation that is also an input file, which is the combination that rots
     * quietly: a renamed key leaves a stanza that looks authoritative and does nothing, and the
     * only symptom is a setting that silently has no effect. This also pins the file's own syntax
     * rule — {@code '#'} comments only at the start of a line — because a trailing {@code # note}
     * would be parsed as part of the value and turn, say, a port into a parse error at boot.
     */
    @Test
    void theShippedExampleConfigLoadsAndUsesOnlyKeysTheProxyReads() throws Exception {
        Path example = Path.of("onilink.example.properties");
        assertTrue(Files.exists(example), "onilink.example.properties is shipped next to the jar");

        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(example)) {
            properties.load(reader);
        }

        for (String key : properties.stringPropertyNames()) {
            assertFalse(properties.getProperty(key).contains("#"),
                    "'" + key + "' has a trailing comment in its value; '#' only starts a comment at "
                            + "the start of a line, so this would be read as part of the setting");
        }

        ProxyConfig config = ProxyConfig.from(properties);

        assertEquals(19132, config.listenAddress().getPort());
        assertEquals("default", config.hubBackendName());
        assertNull(config.backendProtocol(),
                "the example ships backend.protocol=auto, which parses to null and means 'probe it'");
        assertEquals(PacketCompressionAlgorithm.ZLIB, config.compressionAlgorithm());
        assertEquals(false, config.legacyVerification().enabled());
        assertFalse(config.allowlist().enabled());
    }

    /**
     * The operator examples are intended to be copied directly into a deployment. Keep them under
     * the real parser so a renamed key, malformed value, reused secret source, or inline comment
     * fails CI instead of failing on an operator's server.
     */
    @Test
    void deploymentExampleConfigsLoadWithTheDocumentedTopology() throws Exception {
        ProxyConfig single = loadDeploymentExample(
                Path.of("..", "examples", "single-bds", "onilink.properties"));
        assertEquals(1, single.backends().size());
        assertTrue(single.backends().containsKey("survival"));
        assertEquals("ONIBRIDGE_SURVIVAL_SECRET",
                single.backends().get("survival").forwarding().activeSecretEnv());
    }

    private static ProxyConfig loadDeploymentExample(Path example) throws Exception {
        assertTrue(Files.exists(example), "missing deployment example: " + example);
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(example)) {
            properties.load(reader);
        }
        for (String key : properties.stringPropertyNames()) {
            assertFalse(properties.getProperty(key).contains("#"),
                    "'" + key + "' has a trailing inline comment in " + example);
        }
        return ProxyConfig.from(properties, example.toAbsolutePath().getParent());
    }

    /**
     * The config an operator actually gets. Only the jar is uploaded, so {@code loadOrCreate} writing
     * the documented template — not a {@link Properties#store} dump in hash order — is the only thing
     * that puts any configuration documentation on a production box at all.
     */
    @Test
    void aGeneratedConfigIsTheDocumentedTemplateAndLoadsCleanly(@TempDir Path dir) throws Exception {
        Path generated = dir.resolve("config.properties");
        ProxyConfig config = ProxyConfig.loadOrCreate(generated);

        assertTrue(Files.exists(generated), "loadOrCreate must write a config when none exists");
        String written = Files.readString(generated);
        assertTrue(written.contains("#  OniLink Bedrock proxy — configuration"),
                "the generated config must be the documented template, not a bare key dump");
        assertTrue(written.contains("#  BACKENDS — the servers behind the proxy"),
                "the generated config must keep its section headings");
        assertTrue(written.contains("backend.protocol=auto"),
                "a generated config must not pin a protocol version that goes stale at the next "
                        + "backend upgrade — that is the skew that broke the 1.26.40 rollout");

        // A fresh install has to be runnable as written: every backend the template references must
        // be one it also defines.
        assertEquals(1, config.backends().size());
        assertTrue(config.backends().containsKey("default"));
        assertEquals("default", config.hubBackendName());
        assertNull(config.backendProtocol());

        // Re-reading it must give the same thing, so the file and the running config agree.
        ProxyConfig reloaded = ProxyConfig.loadOrCreate(generated);
        assertEquals(config.backends().keySet(), reloaded.backends().keySet());
        assertEquals(config.motd(), reloaded.motd());
    }

    /**
     * The template and {@link ProxyConfig#defaultProperties} are two descriptions of the same
     * defaults, so they drift. A key added to the code but not the template is a setting nobody on a
     * server can discover; a key in the template the proxy does not read is documentation for
     * something that does nothing.
     */
    @Test
    void everyDefaultTheCodeSetsIsDocumentedInTheTemplate() throws Exception {
        Properties template = new Properties();
        try (var input = ProxyConfig.class.getResourceAsStream("/onilink.example.properties")) {
            assertTrue(input != null,
                    "the template is not on the classpath; processResources is no longer shipping it "
                            + "and every generated config would silently fall back to a key dump");
            template.load(input);
        }

        Properties coded = ProxyConfig.defaultProperties();
        for (String key : coded.stringPropertyNames()) {
            assertTrue(template.containsKey(key),
                    "'" + key + "' has a default in ProxyConfig but is undocumented in "
                            + "onilink.example.properties, so nobody running the jar can discover it");
        }
    }

    @Test
    void loadsOverrides() {
        Properties properties = new Properties();
        properties.setProperty("listener.host", "127.0.0.1");
        properties.setProperty("listener.port", "19140");
        properties.setProperty("backend.name", "lobby");
        properties.setProperty("backend.host", "10.0.0.5");
        properties.setProperty("backend.port", "19141");
        properties.setProperty("backends", "lobby,hub,survival");
        properties.setProperty("hubBackend", "hub");
        properties.setProperty("backend.protocol", "1.26.10");
        properties.setProperty("backend.hub.host", "10.0.0.6");
        properties.setProperty("backend.hub.port", "19142");
        properties.setProperty("backend.survival.host", "10.0.0.7");
        properties.setProperty("backend.survival.port", "19143");
        properties.setProperty("legacyVerification.enabled", "false");
        properties.setProperty("legacyVerification.host", "127.0.0.2");
        properties.setProperty("legacyVerification.port", "19142");
        properties.setProperty("legacyVerification.sharedSecret", "secret");
        properties.setProperty("legacyVerification.pendingJoinTtlMillis", "5000");
        properties.setProperty("legacyVerification.requestSkewMillis", "7000");
        properties.setProperty("motd", "Test Proxy");
        properties.setProperty("subMotd", "Local");
        properties.setProperty("gameType", "Creative");
        properties.setProperty("maxPlayers", "5");
        properties.setProperty("compression", "none");
        properties.setProperty("compressionThreshold", "128");

        ProxyConfig config = ProxyConfig.from(properties);

        assertEquals("127.0.0.1", config.listenAddress().getHostString());
        assertEquals(19140, config.listenAddress().getPort());
        assertEquals("lobby", config.backend().name());
        assertEquals("10.0.0.5", config.backend().address().getHostString());
        assertEquals(19141, config.backend().address().getPort());
        assertEquals("hub", config.hubBackendName());
        assertEquals(CanonicalProtocol.V1_26_10, config.backendProtocol());
        assertEquals(3, config.backends().size());
        assertEquals(19142, config.backends().get("hub").address().getPort());
        assertEquals(19143, config.backends().get("survival").address().getPort());
        assertEquals(false, config.legacyVerification().enabled());
        assertEquals("127.0.0.2", config.legacyVerification().listenAddress().getHostString());
        assertEquals(19142, config.legacyVerification().listenAddress().getPort());
        assertEquals("secret", config.legacyVerification().sharedSecret());
        assertEquals(5000, config.legacyVerification().pendingJoinTtlMillis());
        assertEquals(7000, config.legacyVerification().requestSkewMillis());
        assertEquals("Test Proxy", config.motd());
        assertEquals("Local", config.subMotd());
        assertEquals("Creative", config.gameType());
        assertEquals(5, config.maxPlayers());
        assertEquals(PacketCompressionAlgorithm.NONE, config.compressionAlgorithm());
        assertEquals(128, config.compressionThreshold());
    }
}
