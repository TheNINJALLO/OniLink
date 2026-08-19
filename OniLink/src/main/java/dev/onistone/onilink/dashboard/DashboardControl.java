package dev.onistone.onilink.dashboard;

import java.util.List;
import java.util.Map;

/** Runtime boundary used by the HTTP layer and by isolated dashboard integration tests. */
interface DashboardControl extends AutoCloseable {
    Map<String, Object> state();

    List<Map<String, Object>> players(boolean includeAddresses);

    List<Map<String, Object>> backends(boolean includeAddresses);

    ActionResult transfer(String player, String backend);

    ActionResult disconnect(String player, String reason);

    ActionResult alert(String message);

    ActionResult trace(String player, long milliseconds);

    void shutdown();

    @Override
    default void close() {
    }

    record ActionResult(boolean success, String message) {
        Map<String, Object> asMap() {
            return Map.of("success", success, "message", message);
        }
    }
}
