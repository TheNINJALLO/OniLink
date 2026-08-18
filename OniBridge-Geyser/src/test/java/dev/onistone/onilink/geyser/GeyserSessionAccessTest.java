package dev.onistone.onilink.geyser;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GeyserSessionAccessTest {
    @Test
    void readsPreservedLoginAndAppliesVerifiedAddress() throws Exception {
        FakeConnection connection = new FakeConnection();
        GeyserSessionAccess access = new GeyserSessionAccess();

        GeyserSessionAccess.LoginData login = access.read(connection);
        assertEquals("127.0.0.1", login.sourceAddress().getAddress().getHostAddress());
        assertEquals("eyJhbGciOiJFUzM4NCJ9.eyJPbmlGb3J3YXJkIjoiYWJjLmRlZiJ9.c2ln", login.clientDataJwt());

        access.applyRealAddress(connection, "2001:db8::42", 54321);
        assertEquals(54321, connection.upstream.forwarded.getPort());
        assertEquals("2001:db8:0:0:0:0:0:42", connection.upstream.forwarded.getAddress().getHostAddress());
    }

    public static final class FakeConnection {
        private final FakeUpstream upstream = new FakeUpstream();
        private final FakeClientData clientData = new FakeClientData();

        public FakeUpstream getUpstream() {
            return upstream;
        }

        public FakeClientData getClientData() {
            return clientData;
        }
    }

    public static final class FakeUpstream {
        private InetSocketAddress forwarded;

        public InetSocketAddress getAddress() {
            return new InetSocketAddress("127.0.0.1", 19132);
        }

        public void setInetAddress(InetSocketAddress value) {
            forwarded = value;
        }
    }

    public static final class FakeClientData {
        public String getOriginalString() {
            return "eyJhbGciOiJFUzM4NCJ9.eyJPbmlGb3J3YXJkIjoiYWJjLmRlZiJ9.c2ln";
        }
    }
}
