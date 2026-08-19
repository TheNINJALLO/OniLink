package dev.onistone.onilink.plugin;

import dev.onistone.onilink.config.ProxyConfig;
import dev.onistone.onilink.protocol.CanonicalProtocol;
import dev.onistone.onilink.protocol.PacketTranslator;

import java.nio.file.Path;

/** Services available to an addon during {@link OniLinkPlugin#onEnable}. */
public interface PluginContext {

    /** The addon's writable {@code plugins/<name>/} directory. */
    Path dataFolder();

    /** The active proxy configuration. */
    ProxyConfig proxyConfig();

    /** Writes an addon-prefixed message to the proxy console. */
    void info(String message);

    /**
     * Teaches the protocol registry that a client on {@code older} can reach a backend on
     * {@code newer}.
     *
     * The edge becomes part of the shared protocol graph and can affect which clients may join.
     */
    void addProtocolUpgrade(CanonicalProtocol older, CanonicalProtocol newer, PacketTranslator translator);

    /** Adds a loopback-only listener owned by the proxy. */
    void addTrustedListener(TrustedListenerSpec spec);
}
