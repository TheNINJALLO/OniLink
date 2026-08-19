package dev.onistone.onilink.allowlist;

import dev.onistone.onilink.config.AllowlistConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProxyAllowlistTest {
    @Test
    void disabledAllowlistPermitsAnyAuthenticatedXuid(@TempDir Path directory) {
        ProxyAllowlist allowlist = ProxyAllowlist.inMemory(new AllowlistConfig(
                false, directory.resolve("allowlist.properties"), "Not allowed", true));

        assertTrue(allowlist.allows("2533274790000001"));
        assertFalse(allowlist.contains("2533274790000001"));
    }

    @Test
    void enabledAllowlistDefaultsClosedAndPersistsXuids(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("allowlist.properties");
        AllowlistConfig config = new AllowlistConfig(true, file, "Not allowed", true);
        ProxyAllowlist allowlist = ProxyAllowlist.load(config);

        assertFalse(allowlist.allows("2533274790000001"));
        assertTrue(allowlist.add("2533274790000001", "ExamplePlayer"));
        assertTrue(allowlist.allows("2533274790000001"));
        assertFalse(allowlist.allows("2535499999999999"));
        assertTrue(Files.isRegularFile(file));

        ProxyAllowlist reloaded = ProxyAllowlist.load(config);
        assertTrue(reloaded.allows("2533274790000001"));
        assertEquals("ExamplePlayer", reloaded.entries().getFirst().name());
        assertEquals("2533274790000001", reloaded.xuidForLabel("exampleplayer"));

        assertTrue(reloaded.remove("2533274790000001"));
        assertFalse(ProxyAllowlist.load(config).allows("2533274790000001"));
    }

    @Test
    void labelsNeverAuthorizeAndXuidsMustBeDigits(@TempDir Path directory) throws Exception {
        ProxyAllowlist allowlist = ProxyAllowlist.load(new AllowlistConfig(
                true, directory.resolve("allowlist.properties"), "Not allowed", true));
        allowlist.add("2533274790000001", "PlayerName");

        assertFalse(allowlist.allows("PlayerName"));
        assertThrows(IllegalArgumentException.class, () -> allowlist.add("PlayerName", "label"));
        assertThrows(IllegalArgumentException.class, () -> allowlist.add("", "label"));
    }

    @Test
    void malformedExistingFileFailsClosed(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("allowlist.properties");
        Files.writeString(file, "not-an-xuid=Someone\n");

        assertThrows(IllegalArgumentException.class, () -> ProxyAllowlist.load(
                new AllowlistConfig(true, file, "Not allowed", true)));
    }
}
