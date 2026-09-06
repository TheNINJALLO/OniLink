package dev.onistone.onilink.modules.connect;

import dev.onistone.onilink.modules.*;
import dev.onistone.onilink.platform.persistence.PlatformDatabase;
import java.time.*;
import java.util.*;

/** Consent-based social state and bounded group admission queues. All subjects are authenticated XUIDs. */
public final class PlayerNetwork extends ScopedRecords {
    private final ProxyOperations proxy;
    private final Clock clock;
    private NetworkCoordinator coordinator;
    public void coordinator(NetworkCoordinator coordinator) { this.coordinator = coordinator; }
    public PlayerNetwork(PlatformDatabase db, ProxyOperations proxy) { this(db, proxy, Clock.systemUTC()); }
    public PlayerNetwork(PlatformDatabase db, ProxyOperations proxy, Clock clock) { super(db); this.proxy = proxy; this.clock = clock; }
    public Map<String, Object> status(PlatformDatabase.Scope scope) {
        return Map.of("parties", views(database.list(scope, "network-party", 1000)), "queues", views(database.list(scope, "network-queue", 1000)),
                "admission", views(database.list(scope, "network-admission", 1000)));
    }
    public synchronized Map<String, Object> configure(PlatformDatabase.Scope scope, Map<String, Object> input) {
        String backend = required(input, "backend", 64);
        backend(backend);
        int capacity = Math.toIntExact(longValue(input.get("capacity"), 0));
        int reserved = Math.toIntExact(longValue(input.get("reservedSlots"), 0));
        if (capacity < 1 || capacity > 100000 || reserved < 0 || reserved > capacity) throw new IllegalArgumentException("invalid admission capacity");
        List<String> xuids = list(input.getOrDefault("reservedXuids", List.of()));
        if (xuids.size() > 1000 || xuids.stream().anyMatch(x -> !x.matches("[0-9]{1,32}"))) throw new IllegalArgumentException("invalid reserved XUIDs");
        return view(database.put(scope, "network-admission", id(backend), longValue(input.get("revision"), 0),
                Map.of("backend", backend, "capacity", capacity, "reservedSlots", reserved, "reservedXuids", xuids)));
    }
    public synchronized List<String> command(PlatformDatabase.Scope scope, String xuid, List<String> args) {
        player(xuid);
        if (args.isEmpty()) return List.of("/network servers | join <server> | queue cancel | party <create|invite|accept|leave|join|chat> | friend <request|accept|remove|list> | chat <message> | chatmute");
        return switch (args.getFirst().toLowerCase(Locale.ROOT)) {
            case "servers" -> {
                List<String> servers = proxy.backends().stream().filter(b -> available(b) && proxy.mayJoin(xuid, String.valueOf(b.get("name"))))
                        .map(b -> String.valueOf(b.get("name"))).sorted().toList();
                if (!proxy.serverMenu(xuid, servers)) yield List.of("Servers: " + String.join(", ", servers), "Choose with /network join <server>.");
                yield List.of();
            }
            case "join" -> List.of("Queue: " + enqueue(scope, xuid, List.of(xuid), argument(args, 1)).get("state"));
            case "queue" -> {
                if (!argument(args, 1).equals("cancel")) throw new IllegalArgumentException("Usage: /network queue cancel");
                var queued = database.get(scope, "network-queue", xuid);
                if (queued.isPresent() && "MOVING".equals(queued.get().value().get("state"))) throw new IllegalStateException("transfer is already in progress");
                queued.ifPresent(r -> database.delete(scope, r.kind(), r.id(), r.revision()));
                yield List.of("Queue entry cancelled.");
            }
            case "party" -> partyCommand(scope, xuid, args.subList(1, args.size()));
            case "friend" -> friendCommand(scope, xuid, args.subList(1, args.size()));
            case "chat" -> {
                if (database.get(scope, "cluster-policy", "moderation:" + xuid).map(r -> Boolean.TRUE.equals(r.value().get("muted"))
                        && longValue(r.value().get("expiresAt"), Long.MAX_VALUE) > clock.millis()).orElse(false))
                    throw new SecurityException("network chat is muted by network policy");
                var profile = database.get(scope, "network-profile", xuid);
                if (profile.isPresent() && clock.millis() - longValue(profile.get().value().get("lastChat"), 0) < 2000) throw new IllegalStateException("wait before sending another chat message");
                String message = message(args, 1);
                var value = new LinkedHashMap<>(profile.map(PlatformDatabase.StoredRecord::value).orElse(Map.of()));
                value.put("lastChat", clock.millis());
                database.put(scope, "network-profile", xuid, profile.map(PlatformDatabase.StoredRecord::revision).orElse(0L), value);
                String line = "[Network] " + player(xuid).get("name") + ": " + message;
                for (var recipient : proxy.players()) {
                    String target = String.valueOf(recipient.get("xuid"));
                    if (!database.get(scope, "network-profile", target).map(r -> Boolean.TRUE.equals(r.value().get("muteChat"))).orElse(false)) proxy.message(target, line);
                }
                yield List.of();
            }
            case "chatmute" -> {
                var profile = database.get(scope, "network-profile", xuid);
                var value = new LinkedHashMap<>(profile.map(PlatformDatabase.StoredRecord::value).orElse(Map.of()));
                value.put("muteChat", !Boolean.TRUE.equals(value.get("muteChat")));
                database.put(scope, "network-profile", xuid, profile.map(PlatformDatabase.StoredRecord::revision).orElse(0L), value);
                yield List.of("Network chat muted: " + value.get("muteChat"));
            }
            default -> throw new IllegalArgumentException("unknown network command");
        };
    }
    private List<String> partyCommand(PlatformDatabase.Scope scope, String xuid, List<String> args) {
        String action = argument(args, 0);
        var party = party(scope, xuid);
        if (action.equals("create")) {
            if (party.isPresent()) throw new IllegalStateException("you already belong to a party");
            if (database.list(scope, "network-party", 1000).size() >= 1000) throw new IllegalStateException("party limit reached");
            database.put(scope, "network-party", xuid, 0L, Map.of("leader", xuid, "members", List.of(xuid), "invites", Map.of()));
            return List.of("Party created. Invite a player with /network party invite <player>.");
        }
        if (action.equals("accept")) {
            if (party.isPresent()) throw new IllegalStateException("leave your current party first");
            String leader = resolve(argument(args, 1));
            var invited = database.get(scope, "network-party", leader).orElseThrow(() -> new IllegalArgumentException("party not found"));
            var invites = new LinkedHashMap<>(map(invited.value().get("invites")));
            if (longValue(invites.get(xuid), 0) < clock.millis()) throw new IllegalStateException("party invitation expired or absent");
            var members = new ArrayList<>(list(invited.value().get("members")));
            if (members.size() >= 8) throw new IllegalStateException("party is full");
            members.add(xuid); invites.remove(xuid);
            var value = new LinkedHashMap<>(invited.value()); value.put("members", members); value.put("invites", invites);
            database.put(scope, invited.kind(), invited.id(), invited.revision(), value);
            members.forEach(member -> proxy.message(member, player(xuid).get("name") + " joined the party."));
            return List.of();
        }
        var record = party.orElseThrow(() -> new IllegalStateException("you are not in a party"));
        var members = new ArrayList<>(list(record.value().get("members")));
        boolean leader = xuid.equals(record.value().get("leader"));
        if (action.equals("leave")) {
            if (leader) { database.delete(scope, record.kind(), record.id(), record.revision()); members.forEach(member -> proxy.message(member, "Party disbanded.")); }
            else { members.remove(xuid); var value = new LinkedHashMap<>(record.value()); value.put("members", members); database.put(scope, record.kind(), record.id(), record.revision(), value); }
            cancelWaitingGroups(scope, record.id());
            return List.of("You left the party.");
        }
        if (action.equals("chat")) { String text = "[Party] " + player(xuid).get("name") + ": " + message(args, 1); members.forEach(member -> proxy.message(member, text)); return List.of(); }
        if (!leader) throw new IllegalStateException("only the party leader can invite or transfer the group");
        if (action.equals("invite")) {
            String target = resolve(argument(args, 1));
            if (party(scope, target).isPresent()) throw new IllegalStateException("player already belongs to a party");
            var invites = new LinkedHashMap<>(map(record.value().get("invites")));
            invites.entrySet().removeIf(e -> longValue(e.getValue(), 0) < clock.millis());
            if (invites.size() >= 16) throw new IllegalStateException("invitation limit reached");
            invites.put(target, clock.millis() + 300000);
            var value = new LinkedHashMap<>(record.value()); value.put("invites", invites);
            database.put(scope, record.kind(), record.id(), record.revision(), value);
            proxy.message(target, player(xuid).get("name") + " invited you to a party. /network party accept " + player(xuid).get("name"));
            return List.of("Invitation sent; it expires in five minutes.");
        }
        if (action.equals("join")) return List.of("Party queue: " + enqueue(scope, xuid, members, argument(args, 1)).get("state"));
        return List.of("Party members: " + String.join(", ", members));
    }
    private List<String> friendCommand(PlatformDatabase.Scope scope, String xuid, List<String> args) {
        String action = argument(args, 0);
        if (action.equals("list")) return database.list(scope, "network-friend", 10000).stream().filter(r -> list(r.value().get("members")).contains(xuid))
                .map(r -> r.value().get("members") + " — " + r.value().get("state")).limit(100).toList();
        String target = resolve(argument(args, 1));
        if (target.equals(xuid)) throw new IllegalArgumentException("choose another player");
        var members = List.of(xuid, target).stream().sorted().toList();
        String id = members.getFirst() + ":" + members.getLast();
        var existing = database.get(scope, "network-friend", id);
        if (action.equals("remove")) { existing.ifPresent(r -> database.delete(scope, r.kind(), r.id(), r.revision())); return List.of("Friend relationship removed."); }
        if (action.equals("request")) {
            if (existing.isPresent()) throw new IllegalStateException("friend relationship already exists");
            if (database.list(scope, "network-friend", 10000).size() >= 10000) throw new IllegalStateException("friend relationship limit reached");
            database.put(scope, "network-friend", id, 0L, Map.of("members", members, "requestedBy", xuid, "state", "PENDING"));
            proxy.message(target, player(xuid).get("name") + " sent a friend request. /network friend accept " + player(xuid).get("name"));
            return List.of("Friend request sent.");
        }
        if (!action.equals("accept")) throw new IllegalArgumentException("unknown friend action");
        var record = existing.orElseThrow(() -> new IllegalArgumentException("no friend request"));
        if (!"PENDING".equals(record.value().get("state")) || !target.equals(record.value().get("requestedBy"))) throw new IllegalStateException("only the recipient can accept a pending friend request");
        database.put(scope, record.kind(), record.id(), record.revision(), Map.of("members", members, "state", "ACCEPTED", "requestedBy", target));
        proxy.message(target, player(xuid).get("name") + " accepted your friend request.");
        return List.of("Friend request accepted.");
    }
    public synchronized Map<String, Object> enqueue(PlatformDatabase.Scope scope, String leader, List<String> members, String backend) {
        if (!available(backend(backend))) throw new IllegalStateException("server is unavailable or draining");
        for (String member : members) { player(member); if (!proxy.mayJoin(member, backend)) throw new SecurityException("a party member cannot join that backend"); }
        for (var job : database.list(scope, "network-queue", 1000)) if (list(job.value().get("members")).stream().anyMatch(members::contains))
            throw new IllegalStateException("a group member is already queued");
        if (database.list(scope, "network-queue", 1000).size() >= 1000) throw new IllegalStateException("network queue is full");
        var value = Map.<String, Object>of("leader", leader, "members", List.copyOf(members), "backend", backend, "state", "WAITING",
                "deadline", clock.millis() + 1800000, "failures", List.of());
        return view(database.put(scope, "network-queue", leader, 0L, value));
    }
    public synchronized void tick(PlatformDatabase.Scope scope) {
        Map<String, Map<String, Object>> online = new HashMap<>(); proxy.players().forEach(p -> online.put(String.valueOf(p.get("xuid")), p));
        Map<String, Map<String, Object>> backendStates = new HashMap<>(); proxy.backends().forEach(b -> backendStates.put(String.valueOf(b.get("name")), b));
        var jobs = database.list(scope, "network-queue", 1000).stream().sorted(Comparator.comparing(PlatformDatabase.StoredRecord::createdAt)).toList();
        Map<String, Long> occupied = new HashMap<>();
        online.values().forEach(p -> occupied.merge(String.valueOf(p.get("backend")), 1L, Long::sum));
        for (var job : jobs) if ("MOVING".equals(job.value().get("state"))) {
            String target = String.valueOf(job.value().get("backend"));
            list(job.value().get("members")).stream().filter(x -> online.containsKey(x) && !arrived(online.get(x), target)).forEach(x -> occupied.merge(target, 1L, Long::sum));
        }
        for (var job : jobs) {
            String target = String.valueOf(job.value().get("backend")); var members = list(job.value().get("members"));
            boolean moving = "MOVING".equals(job.value().get("state"));
            boolean complete = moving && members.stream().allMatch(x -> online.containsKey(x) && arrived(online.get(x), target));
            if (complete || !backendStates.containsKey(target) || clock.millis() > longValue(job.value().get("deadline"), 0) || !moving && members.stream().anyMatch(x -> !online.containsKey(x))) {
                if (moving && !complete && members.stream().anyMatch(x -> online.containsKey(x) && Boolean.TRUE.equals(online.get(x).get("switching")))) {
                    members.stream().filter(x -> online.containsKey(x) && Boolean.TRUE.equals(online.get(x).get("switching")))
                            .forEach(x -> proxy.disconnect(x, "Network transfer timed out. Please reconnect."));
                    if (coordinator != null) coordinator.lease(scope, job.id(), target, members);
                    continue;
                }
                if (coordinator != null) coordinator.release(scope, job.id());
                database.delete(scope, job.kind(), job.id(), job.revision());
                database.put(scope, "network-transfer-history", UUID.randomUUID().toString(), 0L, Map.of("backend", target, "members", members,
                        "state", complete ? "ARRIVED" : "FAILED", "failures", job.value().get("failures")));
                members.forEach(x -> proxy.message(x, complete ? "Your group arrived at " + target + '.' : "Queue or transfer ended before the whole group arrived."));
                continue;
            }
            if (moving || !available(backendStates.get(target)) || members.stream().anyMatch(x -> !proxy.mayJoin(x, target))) continue;
            var policy = database.get(scope, "network-admission", target).map(PlatformDatabase.StoredRecord::value).orElse(Map.of("capacity", 100, "reservedSlots", 0, "reservedXuids", List.of()));
            boolean reserved = list(policy.get("reservedXuids")).containsAll(members);
            long limit = longValue(policy.get("capacity"), 100) - (reserved ? 0 : longValue(policy.get("reservedSlots"), 0));
            long needed = members.stream().filter(x -> !arrived(online.get(x), target)).count();
            if (occupied.getOrDefault(target, 0L) + needed > limit) continue;
            if (coordinator != null && !coordinator.lease(scope, job.id(), target, members)) continue;
            var value = new LinkedHashMap<>(job.value());
            // Persist before sending requests so restart cannot accidentally admit another group into these slots.
            value.put("state", "MOVING"); value.put("deadline", clock.millis() + 120000);
            var saved = database.put(scope, job.kind(), job.id(), job.revision(), value);
            occupied.merge(target, needed, Long::sum);
            List<String> failures = new ArrayList<>();
            for (String member : members) if (!arrived(online.get(member), target)
                    && !proxy.transfer(String.valueOf(online.get(member).get("name")), target)) failures.add(member);
            if (!failures.isEmpty()) { value.put("failures", failures); database.put(scope, job.kind(), job.id(), saved.revision(), value); }
        }
    }
    private void cancelWaitingGroups(PlatformDatabase.Scope scope, String leader) { database.get(scope, "network-queue", leader).filter(r -> "WAITING".equals(r.value().get("state"))).ifPresent(r -> database.delete(scope, r.kind(), r.id(), r.revision())); }
    private Optional<PlatformDatabase.StoredRecord> party(PlatformDatabase.Scope scope, String xuid) { return database.list(scope, "network-party", 1000).stream().filter(r -> list(r.value().get("members")).contains(xuid)).findFirst(); }
    private Map<String, Object> backend(String name) { return proxy.backends().stream().filter(b -> name.equals(b.get("name"))).findFirst().orElseThrow(() -> new IllegalArgumentException("unknown backend")); }
    private Map<String, Object> player(String xuid) { return proxy.players().stream().filter(p -> xuid.equals(p.get("xuid"))).findFirst().orElseThrow(() -> new IllegalStateException("player is offline")); }
    private String resolve(String value) { if (value.matches("[0-9]{1,32}")) return value; return proxy.players().stream().filter(p -> value.equalsIgnoreCase(String.valueOf(p.get("name")))).map(p -> String.valueOf(p.get("xuid"))).findFirst().orElseThrow(() -> new IllegalArgumentException("player not found; use an XUID for offline friends")); }
    private static boolean available(Map<String, Object> b) { return !Boolean.FALSE.equals(b.get("enabled")) && !Boolean.TRUE.equals(b.get("draining")) && b.get("health") instanceof Map<?, ?> h && "online".equals(h.get("status")); }
    private static boolean arrived(Map<String, Object> p, String b) { return b.equals(p.get("backend")) && !Boolean.TRUE.equals(p.get("switching")); }
    private static String argument(List<String> args, int index) { if (index >= args.size()) throw new IllegalArgumentException("missing command argument"); return args.get(index); }
    private static String message(List<String> args, int index) { String text = String.join(" ", args.subList(Math.min(index, args.size()), args.size())).trim(); if (text.isBlank() || text.length() > 256 || text.chars().anyMatch(c -> c < 32 || c == 167)) throw new IllegalArgumentException("message must contain 1 to 256 plain characters"); return text; }
    private static List<String> list(Object raw) { if (!(raw instanceof List<?> list)) throw new IllegalArgumentException("expected list"); return list.stream().map(String::valueOf).toList(); }
    private static Map<String, Object> map(Object raw) { Map<String, Object> result = new LinkedHashMap<>(); if (raw instanceof Map<?, ?> map) map.forEach((k, v) -> result.put(String.valueOf(k), v)); return result; }
}
