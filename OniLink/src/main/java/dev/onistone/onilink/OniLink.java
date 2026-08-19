package dev.onistone.onilink;

import dev.onistone.onilink.allowlist.ProxyAllowlist;
import dev.onistone.onilink.config.ProxyConfig;
import dev.onistone.onilink.dashboard.OniLinkDashboard;
import dev.onistone.onilink.listener.BedrockProxyListener;
import dev.onistone.onilink.logging.ProxyLogFile;
import dev.onistone.onilink.permissions.ProxyPermissions;
import dev.onistone.onilink.plugin.PluginManager;

import java.nio.file.Path;

/** Entry point for the OniLink Bedrock proxy. */
public final class OniLink {
    private OniLink() {
    }

    public static void main(String[] args) throws Exception {
        Path configPath = args.length > 0 ? Path.of(args[0]) : Path.of("config.properties");
        Path logPath = ProxyLogFile.install(configPath);
        ProxyConfig config = ProxyConfig.loadOrCreate(configPath);
        Path configDirectory = configPath.toAbsolutePath().getParent();
        Path permissionsPath = configPath.toAbsolutePath().resolveSibling("permissions.properties");

        PluginManager pluginManager = new PluginManager(
                configDirectory == null ? Path.of("plugins") : configDirectory.resolve("plugins"),
                config
        );
        pluginManager.enableAll();

        BedrockProxyListener listener = new BedrockProxyListener(
                config,
                ProxyPermissions.load(config.permissions(), permissionsPath),
                ProxyAllowlist.load(config.allowlist()),
                pluginManager
        );

        listener.start();
        OniLinkDashboard dashboard;
        try {
            dashboard = OniLinkDashboard.start(configPath, logPath, config, listener);
        } catch (Exception exception) {
            listener.stop();
            throw exception;
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (dashboard != null) dashboard.close();
            listener.stop();
        }, "onilink-shutdown"));
        try {
            listener.awaitShutdown();
        } finally {
            if (dashboard != null) dashboard.close();
        }
    }
}
