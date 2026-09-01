package dev.onistone.onilink.backend;

import org.cloudburstmc.protocol.bedrock.packet.LoginPacket;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BackendInitialPacketHandlerTest {
    @Test
    void backendLoginCreationIsDeferredUntilTheBackendRequestsLogin() {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger protocolAtIssue = new AtomicInteger(2168);
        BackendInitialPacketHandler handler = new BackendInitialPacketHandler(
                null,
                null,
                "survival",
                null,
                false,
                true,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                () -> {
                    calls.incrementAndGet();
                    LoginPacket login = new LoginPacket();
                    login.setProtocolVersion(protocolAtIssue.get());
                    return login;
                }
        );

        assertEquals(0, calls.get(), "dial setup must not mint a short-lived backend login");
        protocolAtIssue.set(2169);

        LoginPacket issued = handler.issueBackendLogin();

        assertEquals(1, calls.get());
        assertEquals(2169, issued.getProtocolVersion(),
                "the login must reflect state at backend NetworkSettings time");
    }
}
