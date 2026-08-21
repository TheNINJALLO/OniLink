package dev.onistone.onilink.modules.notifications;

import dev.onistone.onilink.modules.ScopedRecords;
import dev.onistone.onilink.platform.events.BoundedEventBus;
import dev.onistone.onilink.platform.events.OniEvent;
import dev.onistone.onilink.platform.events.OniEventType;
import dev.onistone.onilink.platform.persistence.PlatformDatabase;
import dev.onistone.onilink.control.ControlJson;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import org.apache.http.HttpResponse;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Standards-shaped Web Push subscriptions and bounded redacted notification outbox. */
public final class NotificationService extends ScopedRecords implements AutoCloseable {
    public static final Set<String> TOPICS = Set.of(
            "BACKEND_UNHEALTHY", "CONTROL_BRIDGE_DISCONNECTED", "DRAIN_FAILED", "CANARY_FAILED",
            "GREEN_READY_FOR_PROMOTION", "ROLLBACK_COMPLETED", "PLAYER_QUARANTINED",
            "HIGH_PRIORITY_SUPPORT_TICKET", "PACK_SCAN_FAILED", "WORKFLOW_APPROVAL_REQUIRED");
    private final int maxSubscriptions;
    private final String vapidPublicKey;
    private final List<AutoCloseable> subscriptions = new ArrayList<>();
    private final PushService pushService;
    private final ThreadPoolExecutor delivery;
    private final Map<String, Instant> deliveryLimits = new ConcurrentHashMap<>();

    public NotificationService(
            PlatformDatabase database, BoundedEventBus events, int maxSubscriptions, String vapidPublicKey,
            String vapidPrivateKey, String vapidSubject, boolean active
    ) {
        super(database);
        this.maxSubscriptions = maxSubscriptions;
        this.vapidPublicKey = vapidPublicKey == null ? "" : vapidPublicKey.trim();
        this.delivery = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(256), runnable -> {
                    Thread thread = new Thread(runnable, "onilink-web-push");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.DiscardPolicy());
        String privateKey = vapidPrivateKey == null ? "" : vapidPrivateKey.trim();
        try {
            this.pushService = active && !this.vapidPublicKey.isBlank() && !privateKey.isBlank()
                    ? new PushService(this.vapidPublicKey, privateKey,
                    vapidSubject == null || vapidSubject.isBlank()
                            ? "mailto:admin@onilink.local" : vapidSubject.trim())
                    : null;
        } catch (Exception failure) {
            throw new IllegalArgumentException("notifications VAPID keys are invalid", failure);
        }
        if (active) {
            for (OniEventType type : OniEventType.values()) {
                subscriptions.add(events.subscribe(type, this::onEvent));
            }
        }
    }

    public Map<String, Object> snapshot(PlatformDatabase.Scope scope, String user) {
        cleanup(scope);
        List<Map<String, Object>> devices = database.list(scope, "push-subscription", 10_000).stream()
                .filter(record -> user.equals(record.value().get("user")))
                .map(record -> {
                    Map<String, Object> view = new LinkedHashMap<>(ScopedRecords.view(record));
                    view.remove("endpoint");
                    view.remove("p256dh");
                    view.remove("auth");
                    return Map.copyOf(view);
                }).toList();
        List<Map<String, Object>> inbox = database.list(scope, "notification", 500).stream()
                .filter(record -> user.equals(record.value().get("user")))
                .map(ScopedRecords::view).toList();
        return Map.of("vapidPublicKey", vapidPublicKey, "deliveryConfigured", pushService != null,
                "topics", TOPICS, "subscriptions", devices, "inbox", inbox);
    }

    public Map<String, Object> subscribe(
            PlatformDatabase.Scope scope, String user, Map<String, Object> input
    ) {
        long count = database.list(scope, "push-subscription", 10_000).stream()
                .filter(record -> user.equals(record.value().get("user"))).count();
        if (count >= maxSubscriptions) throw new IllegalStateException("device subscription limit reached");
        String endpoint = required(input, "endpoint", 2_048);
        URI parsed;
        try { parsed = URI.create(endpoint); } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("push endpoint is invalid");
        }
        if (!"https".equalsIgnoreCase(parsed.getScheme()) || parsed.getHost() == null) {
            throw new IllegalArgumentException("push endpoint must use HTTPS");
        }
        String p256dh = required(input, "p256dh", 256);
        String auth = required(input, "auth", 128);
        if (!p256dh.matches("[A-Za-z0-9_-]{40,256}") || !auth.matches("[A-Za-z0-9_-]{8,128}")) {
            throw new IllegalArgumentException("push subscription keys are invalid");
        }
        List<String> topics = topics(input.get("topics"));
        String id = UUID.randomUUID().toString();
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("user", user);
        value.put("deviceName", required(input, "deviceName", 80));
        value.put("endpoint", endpoint);
        value.put("p256dh", p256dh);
        value.put("auth", auth);
        value.put("topics", topics);
        value.put("createdAt", Instant.now().toString());
        value.put("lastSuccessAt", "");
        value.put("expiresAt", String.valueOf(input.getOrDefault("expiresAt", "")));
        Map<String, Object> result = new LinkedHashMap<>(view(database.put(scope, "push-subscription", id, null, value)));
        result.remove("endpoint"); result.remove("p256dh"); result.remove("auth");
        return Map.copyOf(result);
    }

    public Map<String, Object> revoke(
            PlatformDatabase.Scope scope, String user, String subscriptionId, long revision, boolean manage
    ) {
        PlatformDatabase.StoredRecord existing = database.get(scope, "push-subscription", id(subscriptionId))
                .orElseThrow(() -> new IllegalArgumentException("unknown subscription"));
        if (!manage && !user.equals(existing.value().get("user"))) {
            throw new SecurityException("subscription belongs to another user");
        }
        database.delete(scope, "push-subscription", existing.id(), revision);
        return Map.of("revoked", true, "subscriptionId", existing.id());
    }

    public Map<String, Object> test(PlatformDatabase.Scope scope, String user) {
        return enqueue(scope, user, "TEST", "OniLink notification test", "/#/notifications");
    }

    public Map<String, Object> enqueue(
            PlatformDatabase.Scope scope, String user, String topic, String summary, String route
    ) {
        String safeTopic = topic == null ? "UNKNOWN" : topic.toUpperCase(Locale.ROOT);
        String safeSummary = summary == null ? "OniLink event" : summary.replaceAll("[\\r\\n]", " ")
                .replaceAll("(?i)\\b(?:bearer|token|secret)\\s*[:=]?\\s*[^\\s]+", "[redacted]")
                .replaceAll("\\b[0-9]{6,20}\\b", "[player]")
                .replaceAll("\\b(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?::[0-9]{1,5})?\\b", "[address]");
        if (safeSummary.length() > 160) safeSummary = safeSummary.substring(0, 160);
        String safeRoute = route != null && route.matches("/#[/A-Za-z0-9._?=&-]{1,200}")
                ? route : "/#/overview";
        String id = UUID.randomUUID().toString();
        Map<String, Object> value = Map.of(
                "user", user, "topic", safeTopic, "summary", safeSummary,
                "route", safeRoute, "createdAt", Instant.now().toString(), "read", false);
        Map<String, Object> stored = view(database.put(scope, "notification", id, null, value));
        trimInbox(scope);
        queueDelivery(scope, user, safeTopic, safeSummary, safeRoute);
        return stored;
    }

    private void onEvent(OniEvent event) {
        String topic = switch (event.type()) {
            case CONTROL_BRIDGE_DISCONNECTED -> "CONTROL_BRIDGE_DISCONNECTED";
            case BACKEND_HEALTH_CHANGED -> "offline".equalsIgnoreCase(String.valueOf(event.data().get("status")))
                    ? "BACKEND_UNHEALTHY" : "";
            case BACKEND_DRAIN_FAILED -> "DRAIN_FAILED";
            case BACKEND_ROLLED_BACK -> "ROLLBACK_COMPLETED";
            case CANARY_RESULT_RECORDED -> Boolean.FALSE.equals(event.data().get("success")) ? "CANARY_FAILED" : "";
            case PLAYER_QUARANTINED -> "PLAYER_QUARANTINED";
            case PACK_SCAN_FAILED -> "PACK_SCAN_FAILED";
            case SUPPORT_TICKET_CREATED -> Boolean.TRUE.equals(event.data().get("highPriority"))
                    ? "HIGH_PRIORITY_SUPPORT_TICKET" : "";
            case PUSH_NOTIFICATION_REQUESTED -> String.valueOf(event.data().getOrDefault("topic", ""));
            default -> "";
        };
        if (topic.isBlank()) return;
        PlatformDatabase.Scope scope = PlatformDatabase.Scope.of(event.tenantId(), event.proxyId());
        Set<String> users = new java.util.LinkedHashSet<>();
        for (PlatformDatabase.StoredRecord subscription : database.list(scope, "push-subscription", 10_000)) {
            if (topics(subscription.value().get("topics")).contains(topic)) {
                users.add(String.valueOf(subscription.value().get("user")));
            }
        }
        for (String user : users) enqueue(scope, user, topic, summary(topic), route(topic));
    }

    private void queueDelivery(
            PlatformDatabase.Scope scope, String user, String topic, String summary, String route
    ) {
        if (pushService == null) return;
        String limitKey = scope.tenantId() + '\0' + scope.proxyId() + '\0' + user + '\0' + topic;
        Instant now = Instant.now();
        Instant previous = deliveryLimits.put(limitKey, now);
        if (previous != null && previous.plusSeconds(2).isAfter(now)) return;
        String payload = ControlJson.encode(Map.of(
                "title", "OniLink", "topic", topic, "summary", summary, "route", route));
        for (PlatformDatabase.StoredRecord subscription : database.list(scope, "push-subscription", 10_000)) {
            if (!user.equals(subscription.value().get("user"))) continue;
            if (!"TEST".equals(topic) && !topics(subscription.value().get("topics")).contains(topic)) continue;
            delivery.execute(() -> deliver(scope, subscription, payload));
        }
    }

    private void deliver(
            PlatformDatabase.Scope scope, PlatformDatabase.StoredRecord subscription, String payload
    ) {
        try {
            Notification notification = new Notification(
                    String.valueOf(subscription.value().get("endpoint")),
                    String.valueOf(subscription.value().get("p256dh")),
                    String.valueOf(subscription.value().get("auth")), payload);
            HttpResponse response = pushService.send(notification);
            int status = response.getStatusLine().getStatusCode();
            PlatformDatabase.StoredRecord current = database.get(
                    scope, "push-subscription", subscription.id()).orElse(null);
            if (current == null) return;
            if (status == 404 || status == 410) {
                database.delete(scope, "push-subscription", current.id(), current.revision());
                return;
            }
            Map<String, Object> updated = new LinkedHashMap<>(current.value());
            if (status >= 200 && status < 300) {
                updated.put("lastSuccessAt", Instant.now().toString());
                updated.remove("lastFailureAt");
            } else {
                updated.put("lastFailureAt", Instant.now().toString());
            }
            database.put(scope, "push-subscription", current.id(), current.revision(), updated);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
            // Endpoints, keys, payloads, and provider response bodies are deliberately not logged.
        }
    }

    private void trimInbox(PlatformDatabase.Scope scope) {
        List<PlatformDatabase.StoredRecord> records = database.list(scope, "notification", 1_000);
        for (int index = 500; index < records.size(); index++) {
            PlatformDatabase.StoredRecord record = records.get(index);
            database.delete(scope, "notification", record.id(), record.revision());
        }
    }

    private void cleanup(PlatformDatabase.Scope scope) {
        Instant now = Instant.now();
        for (PlatformDatabase.StoredRecord record : database.list(scope, "push-subscription", 10_000)) {
            String expiry = String.valueOf(record.value().getOrDefault("expiresAt", ""));
            if (!expiry.isBlank() && Instant.parse(expiry).isBefore(now)) {
                database.delete(scope, "push-subscription", record.id(), record.revision());
            }
        }
    }

    private static List<String> topics(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(String::valueOf).map(item -> item.toUpperCase(Locale.ROOT))
                .filter(TOPICS::contains).distinct().toList();
    }

    private static String summary(String topic) {
        return switch (topic) {
            case "DRAIN_FAILED" -> "A backend drain needs operator attention.";
            case "PACK_SCAN_FAILED" -> "A candidate pack failed validation.";
            case "PLAYER_QUARANTINED" -> "A player was routed to quarantine.";
            case "HIGH_PRIORITY_SUPPORT_TICKET" -> "A high-priority support ticket was opened.";
            default -> "An OniLink operation needs attention.";
        };
    }

    private static String route(String topic) {
        return switch (topic) {
            case "DRAIN_FAILED" -> "/#/continuity";
            case "PACK_SCAN_FAILED" -> "/#/packs";
            case "PLAYER_QUARANTINED" -> "/#/quarantine";
            case "HIGH_PRIORITY_SUPPORT_TICKET" -> "/#/support";
            default -> "/#/platform";
        };
    }

    @Override
    public void close() {
        for (AutoCloseable subscription : subscriptions) {
            try { subscription.close(); } catch (Exception ignored) { }
        }
        delivery.shutdownNow();
    }
}
