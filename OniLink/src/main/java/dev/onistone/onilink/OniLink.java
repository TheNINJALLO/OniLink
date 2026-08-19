package dev.onistone.onilink;

import dev.onistone.onilink.allowlist.ProxyAllowlist;
import dev.onistone.onilink.config.ProxyConfig;
import dev.onistone.onilink.dashboard.OniLinkDashboard;
import dev.onistone.onilink.listener.BedrockProxyListener;
import dev.onistone.onilink.logging.ProxyLogFile;
import dev.onistone.onilink.permissions.ProxyPermissions;
import dev.onistone.onilink.plugin.PluginManager;

import java.nio.file.Path;

/**
 * OniLink: a Velocity-style proxy for Minecraft Bedrock with Endstone/BDS backends.
 *
 * <p>Run it with nothing beside it and it is exactly that — a Bedrock proxy. Drop an addon into
 * {@code plugins/} and it gains whatever that addon adds; an addon adds whatever that addon provides. There is no flag for this and no setting in {@code config.properties}: the addon is either
 * there or it is not.</p>
 *
 * <p>Addons are enabled <em>before</em> the protocol registry is built and the listeners are bound,
 * because that is when they contribute what they need. See {@link PluginManager}.</p>
 */
public final class OniLink {
    private OniLink() {
    }

    public static void main(String[] args) throws Exception {
        Path configPath = args.length > 0 ? Path.of(args[0]) : Path.of("config.properties");
        Path logPath = ProxyLogFile.install(configPath);
        ProxyConfig config = ProxyConfig.loadOrCreate(configPath);
        Path configDirectory = configPath.toAbsolutePath().getParent();
        // Runtime grants live beside the config they extend, so a deployment copies one directory.
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
