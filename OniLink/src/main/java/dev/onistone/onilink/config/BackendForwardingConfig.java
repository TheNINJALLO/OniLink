package dev.onistone.onilink.config;

/** Per-backend OniForward identity and key-selection settings. */
public record BackendForwardingConfig(
        boolean enabled,
        String proxyId,
        String bridgeId,
        String activeKeyId,
        String activeSecretEnv,
        String activeSecretFile,
        String previousKeyId,
        String previousSecretEnv,
        String previousSecretFile,
        long tokenLifetimeMillis
) {
    public BackendForwardingConfig {
        proxyId = clean(proxyId);
        bridgeId = clean(bridgeId);
        activeKeyId = clean(activeKeyId);
        activeSecretEnv = clean(activeSecretEnv);
        activeSecretFile = clean(activeSecretFile);
        previousKeyId = clean(previousKeyId);
        previousSecretEnv = clean(previousSecretEnv);
        previousSecretFile = clean(previousSecretFile);
        if (enabled) {
            if (proxyId.isEmpty() || bridgeId.isEmpty() || activeKeyId.isEmpty()) {
                throw new IllegalArgumentException("enabled OniForward requires proxyId, bridgeId, and activeKeyId");
            }
            if (activeSecretEnv.isEmpty() == activeSecretFile.isEmpty()) {
                throw new IllegalArgumentException("configure exactly one active OniForward secret source");
            }
            if (tokenLifetimeMillis < 1 || tokenLifetimeMillis > 10_000) {
                throw new IllegalArgumentException("OniForward token lifetime must be 1..10000 ms");
            }
        }
    }

    public static BackendForwardingConfig disabled() {
        return new BackendForwardingConfig(false, "", "", "", "", "", "", "", "", 5_000);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}

