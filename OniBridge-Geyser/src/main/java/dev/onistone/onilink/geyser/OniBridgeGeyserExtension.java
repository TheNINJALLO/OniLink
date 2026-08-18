package dev.onistone.onilink.geyser;

import dev.onistone.onilink.geyser.forwarding.OniForwardVerifier;
import dev.onistone.onilink.geyser.forwarding.ReplayCache;
import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.api.event.bedrock.SessionLoginEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserPreInitializeEvent;
import org.geysermc.geyser.api.extension.Extension;

import java.nio.file.Files;
import java.nio.file.Path;

/** Fail-closed Geyser ingress for OniLink-forged Bedrock sessions. */
public final class OniBridgeGeyserExtension implements Extension {
    private record RuntimeState(
            ExtensionConfig config,
            OniForwardVerifier verifier,
            ReplayCache replayCache
    ) {
    }

    private final GeyserSessionAccess sessionAccess = new GeyserSessionAccess();
    private volatile RuntimeState state;

    @Subscribe
    public void onPreInitialize(GeyserPreInitializeEvent event) {
        Path configFile = dataFolder().resolve("config.properties");
        try {
            if (Files.notExists(configFile)) {
                ExtensionConfig.writeTemplate(configFile);
                logger().warning("Wrote config.properties; joins remain rejected until its OniForward secret is configured.");
            }
            ExtensionConfig config = ExtensionConfig.load(configFile);
            state = new RuntimeState(config, new OniForwardVerifier(config.keys()),
                    new ReplayCache(config.replayCacheMaximumEntries()));
            logger().info("OniForward verification enabled for bridge " + config.bridgeId()
                    + " and backend " + config.backendName() + ".");
        } catch (Exception exception) {
            state = null;
            logger().error("OniBridge-Geyser configuration failed; every join will be rejected.", exception);
        }
    }

    @Subscribe
    public void onSessionLogin(SessionLoginEvent event) {
        String playerName = event.connection().bedrockUsername();
        RuntimeState runtime = state;
        if (runtime == null) {
            reject(event, playerName, "extension is not securely configured");
            return;
        }

        try {
            GeyserSessionAccess.LoginData login = sessionAccess.read(event.connection());
            if (!runtime.config().trustedProxies().matches(login.sourceAddress().getAddress())) {
                reject(event, playerName, "source address is not a trusted proxy");
                return;
            }
            String clientDataJson = ClientDataJwt.payloadJson(login.clientDataJwt());
            String token = TopLevelJson.uniqueString(clientDataJson, "OniForward");
            long now = System.currentTimeMillis();
            OniForwardVerifier.Result result = runtime.verifier().verify(token, new OniForwardVerifier.Validation(
                    playerName,
                    event.connection().xuid(),
                    runtime.config().bridgeId(),
                    runtime.config().backendName(),
                    now,
                    runtime.config().maximumLifetimeMs(),
                    runtime.config().allowedClockSkewMs(),
                    runtime.config().maximumTokenSize()));
            if (!result.valid()) {
                reject(event, playerName, result.error());
                return;
            }
            long replayRetention = saturatedAdd(result.claims().expiresAtMs(), runtime.config().allowedClockSkewMs());
            if (!runtime.replayCache().consume(result.claims(), now, replayRetention)) {
                reject(event, playerName, "OniForward token was replayed or replay protection is full");
                return;
            }
            sessionAccess.applyRealAddress(event.connection(), result.claims().realIp(), result.claims().realPort());
            logger().debug("Accepted verified OniForward session for " + playerName + ".");
        } catch (Exception exception) {
            reject(event, playerName, "local verification or address forwarding failed");
            logger().error("OniBridge-Geyser could not process a login for " + playerName + ".", exception);
        }
    }

    private void reject(SessionLoginEvent event, String playerName, String reason) {
        logger().warning("Rejected join for " + playerName + ": " + reason);
        event.setCancelled(true, "Proxy verification failed.");
    }

    private static long saturatedAdd(long value, long increment) {
        if (increment > 0 && value > Long.MAX_VALUE - increment) {
            return Long.MAX_VALUE;
        }
        return value + increment;
    }
}
