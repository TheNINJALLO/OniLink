package dev.onistone.onilink.plugin;

/** Lifecycle contract for addons loaded from {@code plugins/}. */
public interface OniLinkPlugin {

    /**
     * Registers protocol edges and listeners before the proxy binds its sockets.
     */
    void onEnable(PluginContext context) throws Exception;

    /** Called after all listeners are accepting connections. */
    default void onProxyReady() throws Exception {
    }

    /** Called in reverse enable order during shutdown. */
    default void onDisable() {
    }
}
