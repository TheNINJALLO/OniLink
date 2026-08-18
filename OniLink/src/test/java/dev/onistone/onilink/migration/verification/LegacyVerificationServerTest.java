package dev.onistone.onilink.migration.verification;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class LegacyVerificationServerTest {
    @Test
    void returnsAuthenticatedIdentityAndRealClientEndpoint() {
        PendingJoin pendingJoin = new PendingJoin(
                "2535459084817261",
                "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
                "Player One",
                "skygen",
                "2001:db8::42",
                42123,
                123456L
        );

        assertEquals(
                "{\"status\":\"ok\","
                        + "\"xuid\":\"2535459084817261\","
                        + "\"uuid\":\"aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee\","
                        + "\"name\":\"Player One\","
                        + "\"backend\":\"skygen\","
                        + "\"real_ip\":\"2001:db8::42\","
                        + "\"real_port\":42123}",
                LegacyVerificationServer.verificationResponse(pendingJoin)
        );
    }
}
