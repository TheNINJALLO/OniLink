package dev.onistone.onilink.listener;

import org.cloudburstmc.protocol.bedrock.packet.ClientToServerHandshakePacket;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacketHandler;
import org.cloudburstmc.protocol.bedrock.packet.LoginPacket;
import org.cloudburstmc.protocol.bedrock.packet.RequestNetworkSettingsPacket;
import org.cloudburstmc.protocol.bedrock.packet.ServerToClientHandshakePacket;
import org.cloudburstmc.protocol.common.PacketSignal;
import dev.onistone.onilink.auth.ClientLogin;
import dev.onistone.onilink.auth.ClientLoginAuthenticator;
import dev.onistone.onilink.auth.OfflineLoginForge;
import dev.onistone.onilink.allowlist.ProxyAllowlist;
import dev.onistone.onilink.codec.CodecDefinitionState;
import dev.onistone.onilink.backend.BackendConnector;
import dev.onistone.onilink.backend.ProxyConnection;
import dev.onistone.onilink.backend.UnsupportedVersionPairException;
import dev.onistone.onilink.crypto.BedrockCrypto;
import dev.onistone.onilink.network.NetworkSettingsNegotiationResult;
import dev.onistone.onilink.network.NetworkSettingsNegotiator;
import dev.onistone.onilink.protocol.CanonicalProtocol;
import dev.onistone.onilink.protocol.IdentityTranslator898;
import dev.onistone.onilink.protocol.PacketMonitor;
import dev.onistone.onilink.control.OniControlRuntime;
import dev.onistone.onilink.protocol.TranslationContext;
import dev.onistone.onilink.registry.BackendPaletteStore;
import dev.onistone.onilink.resourcepack.BackendPackCache;
import dev.onistone.onilink.resourcepack.ProxyResourcePackRegistry;
import dev.onistone.onilink.session.ProxySessionProfile;
import dev.onistone.onilink.session.ConnectedPlayerRegistry;

import javax.crypto.SecretKey;
import java.security.KeyPair;

public final class InitialClientPacketHandler implements BedrockPacketHandler {
    private final ListenerSession session;
    private final NetworkSettingsNegotiator networkSettingsNegotiator;
    private final BackendConnector backendConnector;
    private final ClientLoginAuthenticator authenticator;
    private final OfflineLoginForge offlineLoginForge;
    private final ConnectedPlayerRegistry connectedPlayers;
    private final Runnable playerCountChanged;
    private final ProxyResourcePackRegistry proxyResourcePackRegistry;
    private final BackendPaletteStore backendPaletteStore;
    private final BackendPackCache backendPackCache;
    private final ProxyAllowlist allowlist;
    private final PacketMonitor packetMonitor;
    private final OniControlRuntime oniControlRuntime;
    private SecretKey clientEncryptionKey;
    private ProxyConnection connection;

    public InitialClientPacketHandler(
            ListenerSession session,
            NetworkSettingsNegotiator networkSettingsNegotiator,
            BackendConnector backendConnector,
            ClientLoginAuthenticator authenticator,
            OfflineLoginForge offlineLoginForge,
            ConnectedPlayerRegistry connectedPlayers,
            Runnable playerCountChanged
    ) {
        this(session, networkSettingsNegotiator, backendConnector, authenticator, offlineLoginForge,
                connectedPlayers, playerCountChanged, ProxyResourcePackRegistry.empty());
    }

    public InitialClientPacketHandler(
            ListenerSession session,
            NetworkSettingsNegotiator networkSettingsNegotiator,
            BackendConnector backendConnector,
            ClientLoginAuthenticator authenticator,
            OfflineLoginForge offlineLoginForge,
            ConnectedPlayerRegistry connectedPlayers,
            Runnable playerCountChanged,
            ProxyResourcePackRegistry proxyResourcePackRegistry
    ) {
        this(session, networkSettingsNegotiator, backendConnector, authenticator, offlineLoginForge,
                connectedPlayers, playerCountChanged, proxyResourcePackRegistry, BackendPaletteStore.disabled());
    }

    public InitialClientPacketHandler(
            ListenerSession session,
            NetworkSettingsNegotiator networkSettingsNegotiator,
            BackendConnector backendConnector,
            ClientLoginAuthenticator authenticator,
            OfflineLoginForge offlineLoginForge,
            ConnectedPlayerRegistry connectedPlayers,
            Runnable playerCountChanged,
            ProxyResourcePackRegistry proxyResourcePackRegistry,
            BackendPaletteStore backendPaletteStore
    ) {
        this(session, networkSettingsNegotiator, backendConnector, authenticator, offlineLoginForge,
                connectedPlayers, playerCountChanged, proxyResourcePackRegistry, backendPaletteStore,
                BackendPackCache.disabled(), ProxyAllowlist.disabled());
    }

    public InitialClientPacketHandler(
            ListenerSession session,
            NetworkSettingsNegotiator networkSettingsNegotiator,
            BackendConnector backendConnector,
            ClientLoginAuthenticator authenticator,
            OfflineLoginForge offlineLoginForge,
            ConnectedPlayerRegistry connectedPlayers,
            Runnable playerCountChanged,
            ProxyResourcePackRegistry proxyResourcePackRegistry,
            BackendPaletteStore backendPaletteStore,
            BackendPackCache backendPackCache
    ) {
        this(session, networkSettingsNegotiator, backendConnector, authenticator, offlineLoginForge,
                connectedPlayers, playerCountChanged, proxyResourcePackRegistry, backendPaletteStore,
                backendPackCache, ProxyAllowlist.disabled());
    }

    public InitialClientPacketHandler(
            ListenerSession session,
            NetworkSettingsNegotiator networkSettingsNegotiator,
            BackendConnector backendConnector,
            ClientLoginAuthenticator authenticator,
            OfflineLoginForge offlineLoginForge,
            ConnectedPlayerRegistry connectedPlayers,
            Runnable playerCountChanged,
            ProxyResourcePackRegistry proxyResourcePackRegistry,
            BackendPaletteStore backendPaletteStore,
            BackendPackCache backendPackCache,
            ProxyAllowlist allowlist
    ) {
        this(session, networkSettingsNegotiator, backendConnector, authenticator, offlineLoginForge,
                connectedPlayers, playerCountChanged, proxyResourcePackRegistry, backendPaletteStore,
                backendPackCache, allowlist, null);
    }

    public InitialClientPacketHandler(
            ListenerSession session,
            NetworkSettingsNegotiator networkSettingsNegotiator,
            BackendConnector backendConnector,
            ClientLoginAuthenticator authenticator,
            OfflineLoginForge offlineLoginForge,
            ConnectedPlayerRegistry connectedPlayers,
            Runnable playerCountChanged,
            ProxyResourcePackRegistry proxyResourcePackRegistry,
            BackendPaletteStore backendPaletteStore,
            BackendPackCache backendPackCache,
            ProxyAllowlist allowlist,
            PacketMonitor packetMonitor
    ) {
        this(session, networkSettingsNegotiator, backendConnector, authenticator, offlineLoginForge,
                connectedPlayers, playerCountChanged, proxyResourcePackRegistry, backendPaletteStore,
                backendPackCache, allowlist, packetMonitor, null);
    }

    public InitialClientPacketHandler(
            ListenerSession session,
            NetworkSettingsNegotiator networkSettingsNegotiator,
            BackendConnector backendConnector,
            ClientLoginAuthenticator authenticator,
            OfflineLoginForge offlineLoginForge,
            ConnectedPlayerRegistry connectedPlayers,
            Runnable playerCountChanged,
            ProxyResourcePackRegistry proxyResourcePackRegistry,
            BackendPaletteStore backendPaletteStore,
            BackendPackCache backendPackCache,
            ProxyAllowlist allowlist,
            PacketMonitor packetMonitor,
            OniControlRuntime oniControlRuntime
    ) {
        this.session = session;
        this.networkSettingsNegotiator = networkSettingsNegotiator;
        this.backendConnector = backendConnector;
        this.authenticator = authenticator;
        this.offlineLoginForge = offlineLoginForge;
        this.connectedPlayers = connectedPlayers;
        this.playerCountChanged = playerCountChanged;
        this.proxyResourcePackRegistry = proxyResourcePackRegistry != null
                ? proxyResourcePackRegistry
                : ProxyResourcePackRegistry.empty();
        this.backendPaletteStore = backendPaletteStore != null
                ? backendPaletteStore
                : BackendPaletteStore.disabled();
        this.backendPackCache = backendPackCache != null ? backendPackCache : BackendPackCache.disabled();
        this.allowlist = allowlist != null ? allowlist : ProxyAllowlist.disabled();
        this.packetMonitor = packetMonitor;
        this.oniControlRuntime = oniControlRuntime;
    }

    @Override
    public PacketSignal handle(RequestNetworkSettingsPacket packet) {
        NetworkSettingsNegotiationResult result = networkSettingsNegotiator.handle(packet);
        if (result instanceof NetworkSettingsNegotiationResult.Accepted accepted) {
            session.setClientCodec(accepted.clientCodec());
            session.setCodec(accepted.clientCodec());
            CodecDefinitionState.installFallbacks(session);
            observePreLoginPacket(packet, accepted.clientCodec());
            session.sendPacketImmediately(accepted.networkSettings());
            session.setCompression(accepted.networkSettings().getCompressionAlgorithm());
            if (ProxyConnection.isPacketTracingConfigured()) {
                System.out.printf(
                        "Accepted %s using protocol %d.%n",
                        session.getSocketAddress(),
                        accepted.clientCodec().getProtocolVersion()
                );
            }
            return PacketSignal.HANDLED;
        }

        NetworkSettingsNegotiationResult.Rejected rejected = (NetworkSettingsNegotiationResult.Rejected) result;
        session.sendPacketImmediately(rejected.playStatus());
        session.disconnect("disconnectionScreen.outdatedClient");
        // The protocol number is the point of this line. A client newer than the proxy is how a new
        // Minecraft release announces itself, and that number is the first thing needed to add
        // support for it — without it the only clue is "somebody could not join".
        System.out.printf(
                "Rejected %s with %s: client protocol %d, proxy speaks up to %d (%s).%n",
                session.getSocketAddress(),
                rejected.playStatus().getStatus(),
                rejected.requestedProtocol(),
                CanonicalProtocol.newest().protocolVersion(),
                CanonicalProtocol.newest().minecraftVersion()
        );
        return PacketSignal.HANDLED;
    }

    @Override
    public PacketSignal handle(LoginPacket packet) {
        try {
            if (session.clientCodec() == null) {
                session.disconnect("Network settings have not been negotiated");
                return PacketSignal.HANDLED;
            }

            ClientLogin clientLogin = authenticator.authenticate(packet);
            String xuid = clientLogin.authData().xuid();
            if (!allowlist.allows(xuid)) {
                System.out.printf(
                        "Denied allowlist join for %s (XUID %s) from %s.%n",
                        clientLogin.authData().displayName(),
                        xuid,
                        session.getSocketAddress()
                );
                session.disconnect(allowlist.config().kickMessage());
                return PacketSignal.HANDLED;
            }
            KeyPair keyPair = BedrockCrypto.createKeyPair();
            byte[] token = BedrockCrypto.randomToken();
            clientEncryptionKey = BedrockCrypto.secretKey(keyPair.getPrivate(), clientLogin.identityPublicKey(), token);

            connection = new ProxyConnection(
                    session,
                    new ProxySessionProfile(
                            session.clientCodec(),
                            session.clientCodec(),
                            session.clientCodec(),
                            IdentityTranslator898.INSTANCE
                    ),
                    clientLogin,
                    keyPair,
                    offlineLoginForge.forge(keyPair, clientLogin),
                    proxyResourcePackRegistry,
                    backendPaletteStore,
                    backendPackCache,
                    packetMonitor
            );
            connection.setOniControlRuntime(oniControlRuntime);
            connection.journeyTrace().mark(dev.onistone.onilink.modules.pulse.JourneyTrace.Stage.PROTOCOL_NEGOTIATED);
            connection.journeyTrace().mark(dev.onistone.onilink.modules.pulse.JourneyTrace.Stage.AUTH_STARTED);
            connection.journeyTrace().mark(dev.onistone.onilink.modules.pulse.JourneyTrace.Stage.AUTH_COMPLETED);
            connection.observePacket(
                    PacketMonitor.Direction.SERVERBOUND,
                    packet,
                    packet,
                    PacketMonitor.Action.HANDLED
            );

            ConnectedPlayerRegistry.RegistrationResult registration = connectedPlayers.register(connection);
            if (registration == ConnectedPlayerRegistry.RegistrationResult.DUPLICATE_XUID) {
                session.disconnect("This Xbox account is already connected to the proxy");
                connection = null;
                clientEncryptionKey = null;
                return PacketSignal.HANDLED;
            }
            if (registration == ConnectedPlayerRegistry.RegistrationResult.FULL) {
                session.disconnect("Proxy is full");
                connection = null;
                clientEncryptionKey = null;
                return PacketSignal.HANDLED;
            }
            session.setProxyConnection(connection);
            playerCountChanged.run();
            System.out.printf(
                    "Player %s (XUID %s) joined the proxy from %s%s.%n",
                    clientLogin.authData().displayName(),
                    clientLogin.authData().xuid(),
                    // A bridged player's socket address is the bridge's loopback one, which is identical
                    // for all of them. Report the address the bridge stamped in instead.
                    connection.clientAddress(),
                    clientLogin.isJavaEdition() ? " (a bridged edition)" : ""
            );

            ServerToClientHandshakePacket handshake = new ServerToClientHandshakePacket();
            handshake.setJwt(BedrockCrypto.handshakeJwt(keyPair, token));
            session.sendPacketImmediately(handshake);
            session.enableEncryption(clientEncryptionKey);
            return PacketSignal.HANDLED;
        } catch (Exception exception) {
            session.disconnect("Unable to authenticate with Xbox Live");
            throw new IllegalStateException("Unable to authenticate client login", exception);
        }
    }

    @Override
    public PacketSignal handle(ClientToServerHandshakePacket packet) {
        if (connection == null || clientEncryptionKey == null) {
            session.disconnect("Login handshake was not initialized");
            return PacketSignal.HANDLED;
        }

        try {
            connection.journeyTrace().mark(
                    dev.onistone.onilink.modules.pulse.JourneyTrace.Stage.BACKEND_CONNECT_STARTED);
            connection.observePacket(
                    PacketMonitor.Direction.SERVERBOUND,
                    packet,
                    packet,
                    PacketMonitor.Action.HANDLED
            );
            backendConnector.connect(connection);
        } catch (Exception exception) {
            // connect() reports failure through the activation before it throws, so by now the join
            // try-list may already be working on the next candidate. Kicking here would end the
            // session it is trying to save.
            if (connection.isJoinSequenceActive()) {
                return PacketSignal.HANDLED;
            }
            session.disconnect(exception instanceof UnsupportedVersionPairException
                    ? exception.getMessage()
                    : "Unable to connect to backend server");
            throw new IllegalStateException("Unable to connect to backend server", exception);
        }
        return PacketSignal.HANDLED;
    }

    private void observePreLoginPacket(
            org.cloudburstmc.protocol.bedrock.packet.BedrockPacket packet,
            org.cloudburstmc.protocol.bedrock.codec.BedrockCodec codec
    ) {
        if (packetMonitor == null || packet == null || codec == null) {
            return;
        }
        org.cloudburstmc.protocol.bedrock.netty.BedrockPacketWrapper wrapper =
                session.currentInboundPacket();
        byte[] wireBytes = null;
        int headerLength = 0;
        if (wrapper != null && wrapper.getPacketBuffer() != null) {
            wireBytes = io.netty.buffer.ByteBufUtil.getBytes(
                    wrapper.getPacketBuffer(),
                    wrapper.getPacketBuffer().readerIndex(),
                    wrapper.getPacketBuffer().readableBytes(),
                    false
            );
            headerLength = wrapper.getHeaderLength();
        }
        packetMonitor.observe(
                PacketMonitor.Direction.SERVERBOUND,
                packet,
                packet,
                PacketMonitor.Action.HANDLED,
                new TranslationContext(codec, codec, codec),
                new PacketMonitor.CaptureContext(
                        "",
                        "",
                        String.valueOf(session.getSocketAddress()),
                        "",
                        "",
                        wireBytes,
                        headerLength
                )
        );
    }
}
