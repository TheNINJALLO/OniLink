package dev.onistone.onilink.control;

import dev.onistone.onilink.config.OniControlConfig;
import dev.onistone.onilink.packet.OniPacketFactory;
import dev.onistone.onilink.session.ConnectedPlayerRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolLabServiceTest {
    @Test
    void disabledLabCannotStartAndExposesOnlyReviewedModels() {
        ProtocolLabService service = service(false);
        assertThrows(IllegalStateException.class, () -> service.start("owner"));
        Map<String, Object> status = service.status("owner");
        assertFalse((Boolean) status.get("enabled"));
        assertTrue(String.valueOf(status.get("models")).contains("SYSTEM_MESSAGE"));
        assertFalse(String.valueOf(status.get("models")).toLowerCase().contains("login"));
    }

    @Test
    void sessionMustBeStartedAndBackendBoundModelsFailClosed() {
        ProtocolLabService service = service(true);
        Map<String, String> request = Map.of(
                "xuid", "1000000000000001", "backend", "survival",
                "direction", "BACKEND_BOUND", "model", "SYSTEM_MESSAGE", "payload", "{}");
        assertThrows(IllegalStateException.class, () -> service.validate("owner", request, false));
        service.start("owner");
        assertTrue((Boolean) service.status("owner").get("sessionActive"));
        assertThrows(IllegalArgumentException.class, () -> service.validate("owner", request, false));
        assertTrue((Boolean) service.stop("owner").get("stopped"));
    }

    private static ProtocolLabService service(boolean enabled) {
        OniControlConfig.ProtocolLabConfig config = new OniControlConfig.ProtocolLabConfig(
                enabled, false, 5, 60, Set.of("1000000000000001"), Set.of("survival"));
        return new ProtocolLabService(config, new ConnectedPlayerRegistry(10),
                new OniPacketFactory(), new AtomicInteger(1));
    }
}
