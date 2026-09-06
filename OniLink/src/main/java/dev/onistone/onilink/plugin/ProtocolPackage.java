package dev.onistone.onilink.plugin;

import dev.onistone.onilink.protocol.ProtocolRegistry;

/** Version 1 trusted in-process protocol extension. Instances must contribute stateless translators. */
public interface ProtocolPackage {
    int API_VERSION = 1;
    void contribute(ProtocolRegistry.Builder registry);
}
