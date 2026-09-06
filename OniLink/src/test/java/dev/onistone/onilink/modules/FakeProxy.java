package dev.onistone.onilink.modules;

import java.util.*;

public final class FakeProxy implements ProxyOperations {
    public final Map<String, Map<String, Object>> online = new LinkedHashMap<>();
    public final Map<String, Boolean> draining = new HashMap<>();
    public final Set<String> offline = new HashSet<>();
    public final List<String> transfers = new ArrayList<>();
    public final List<String> messages = new ArrayList<>();
    public boolean accept = true;
    public boolean mayJoin(String xuid, String backend) { return true; }
    private dev.onistone.onilink.protocol.ProtocolRegistry protocols = dev.onistone.onilink.protocol.ProtocolRegistry.createDefault();
    public dev.onistone.onilink.protocol.ProtocolRegistry protocols() { return protocols; }
    public void installProtocols(dev.onistone.onilink.protocol.ProtocolRegistry registry) { protocols = registry; }
    public List<dev.onistone.onilink.resourcepack.ProxyResourcePackEntry> packs = List.of();
    public void installPacks(List<dev.onistone.onilink.resourcepack.ProxyResourcePackEntry> entries) { packs = List.copyOf(entries); }
    public void player(String xuid, String backend, boolean switching) {
        online.put(xuid, Map.of("xuid", xuid, "name", "player" + xuid, "backend", backend, "switching", switching));
    }
    public List<Map<String, Object>> players() { return List.copyOf(online.values()); }
    public List<Map<String, Object>> backends() { return List.of("game", "limbo", "other").stream()
            .map(name -> Map.<String, Object>of("name", name, "health", Map.of("status", offline.contains(name) ? "offline" : "online", "advertisedVersion", "1.26.45", "checkedAt", java.time.Instant.now().toString()),
                    "enabled", true, "draining", draining.getOrDefault(name, false), "maxPlayers", 10)).toList(); }
    public Map<String, Object> backendRegistry() { return Map.of("revision", 1); }
    public Map<String, Object> registerBackend(Map<String, String> v) { return Map.of(); }
    public Map<String, Object> updateBackend(Map<String, String> v) { return Map.of(); }
    public Map<String, Object> removeBackend(Map<String, String> v) { return Map.of(); }
    public Map<String, Object> setBackendDraining(String b, boolean d, long r) { draining.put(b, d); return Map.of(); }
    public Map<String, Object> setBackendEnabled(String b, boolean e, long r) { return Map.of(); }
    public Map<String, Object> controlStatus() { return Map.of(); }
    public boolean transfer(String name, String backend) { transfers.add(name + ":" + backend); return accept; }
    public boolean message(String xuid, String message) { messages.add(xuid + ":" + message); return online.containsKey(xuid); }
    public boolean trace(String xuid, long milliseconds) { return true; }
}
