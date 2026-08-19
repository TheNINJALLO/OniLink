package dev.onistone.onilink.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DashboardConfigTest {
    @Test
    void defaultsToAEnabledLoopbackOnlyDashboard(@TempDir Path directory) {
        DashboardConfig config = DashboardConfig.from(new Properties(), directory);

        assertTrue(config.enabled());
        assertEquals("127.0.0.1", config.listenAddress().getHostString());
        assertEquals(8080, config.listenAddress().getPort());
        assertEquals(directory.resolve("dashboard").toAbsolutePath().normalize(), config.dataDirectory());
        assertEquals(480, config.sessionMinutes());
    }

    @Test
    void readsDeploymentOverrides(@TempDir Path directory) {
        Properties properties = new Properties();
        properties.setProperty("dashboard.enabled", "false");
        properties.setProperty("dashboard.host", "0.0.0.0");
        properties.setProperty("dashboard.port", "19132");
        properties.setProperty("dashboard.sessionMinutes", "60");
        properties.setProperty("dashboard.dataDirectory", "state/dashboard");
        properties.setProperty("dashboard.maxRequestBytes", "65536");
        properties.setProperty("dashboard.logTailLines", "1000");

        DashboardConfig config = DashboardConfig.from(properties, directory);

        assertFalse(config.enabled());
        assertEquals("0.0.0.0", config.listenAddress().getHostString());
        assertEquals(19132, config.listenAddress().getPort());
        assertEquals(60, config.sessionMinutes());
        assertEquals(directory.resolve("state/dashboard").toAbsolutePath().normalize(), config.dataDirectory());
        assertEquals(65_536, config.maxRequestBytes());
        assertEquals(1_000, config.logTailLines());
    }
}
