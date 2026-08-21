package dev.onistone.onilink.modules;

import java.util.List;
import java.util.Map;

/** Narrow live-proxy boundary used by expansion services without exposing packet internals. */
public interface ProxyOperations {
    List<Map<String, Object>> players();
    List<Map<String, Object>> backends();
    Map<String, Object> backendRegistry();
    Map<String, Object> registerBackend(Map<String, String> values);
    Map<String, Object> updateBackend(Map<String, String> values);
    Map<String, Object> removeBackend(Map<String, String> values);
    Map<String, Object> setBackendDraining(String backend, boolean draining, long revision);
    Map<String, Object> setBackendEnabled(String backend, boolean enabled, long revision);
    Map<String, Object> controlStatus();
    boolean transfer(String displayName, String backend);
    boolean message(String xuid, String message);
    boolean trace(String xuid, long milliseconds);
}
