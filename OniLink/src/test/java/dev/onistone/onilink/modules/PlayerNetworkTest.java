package dev.onistone.onilink.modules;

import dev.onistone.onilink.modules.connect.PlayerNetwork;
import dev.onistone.onilink.platform.persistence.PlatformDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PlayerNetworkTest {
    @TempDir Path directory;
    final PlatformDatabase.Scope scope = PlatformDatabase.Scope.of("tenant", "main");
    @Test void partiesRequireConsentAndWholeGroupCapacityThenConfirmArrival() throws Exception {
        var proxy = new FakeProxy(); proxy.player("1", "limbo", false); proxy.player("2", "limbo", false);
        try (var db = new PlatformDatabase(directory)) {
            var service = new PlayerNetwork(db, proxy);
            service.command(scope, "1", List.of("party", "create"));
            assertThrows(IllegalStateException.class, () -> service.command(scope, "2", List.of("party", "accept", "1")));
            service.command(scope, "1", List.of("party", "invite", "2"));
            service.command(scope, "2", List.of("party", "accept", "1"));
            assertThrows(IllegalStateException.class, () -> service.command(scope, "2", List.of("party", "join", "game")));
            service.configure(scope, Map.of("backend", "game", "capacity", 2, "reservedSlots", 1, "revision", 0));
            service.command(scope, "1", List.of("party", "join", "game"));
            service.tick(scope); assertTrue(proxy.transfers.isEmpty());
            service.configure(scope, Map.of("backend", "game", "capacity", 2, "reservedSlots", 0, "revision", 1));
            service.tick(scope); assertEquals(2, proxy.transfers.size());
            assertEquals("MOVING", db.get(scope, "network-queue", "1").orElseThrow().value().get("state"));
            service.tick(scope); assertEquals(2, proxy.transfers.size());
            proxy.player("1", "game", false); proxy.player("2", "game", true);
            service.tick(scope); assertTrue(db.get(scope, "network-queue", "1").isPresent());
            proxy.player("2", "game", false); service.tick(scope);
            assertTrue(db.get(scope, "network-queue", "1").isEmpty());
            assertEquals("ARRIVED", db.list(scope, "network-transfer-history", 10).getFirst().value().get("state"));
            assertTrue(db.list(PlatformDatabase.Scope.of("other", "main"), "network-party", 10).isEmpty());
        }
    }
    @Test void friendsRequireRecipientConsentAndChatRespectsMuteAndThrottle() throws Exception {
        var proxy = new FakeProxy(); proxy.player("1", "limbo", false); proxy.player("2", "game", false);
        try (var db = new PlatformDatabase(directory)) {
            var service = new PlayerNetwork(db, proxy);
            service.command(scope, "1", List.of("friend", "request", "2"));
            assertThrows(IllegalStateException.class, () -> service.command(scope, "1", List.of("friend", "accept", "2")));
            service.command(scope, "2", List.of("friend", "accept", "1"));
            assertEquals("ACCEPTED", db.list(scope, "network-friend", 10).getFirst().value().get("state"));
            service.command(scope, "2", List.of("chatmute")); proxy.messages.clear();
            service.command(scope, "1", List.of("chat", "hello"));
            assertEquals(List.of("1:[Network] player1: hello"), proxy.messages);
            assertThrows(IllegalStateException.class, () -> service.command(scope, "1", List.of("chat", "spam")));
        }
    }
}
