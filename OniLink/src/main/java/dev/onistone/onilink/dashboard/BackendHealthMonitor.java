package dev.onistone.onilink.dashboard;

import dev.onistone.onilink.config.BackendConfig;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/** Bounded RakNet unconnected-ping health checks for configured Bedrock backends. */
final class BackendHealthMonitor implements AutoCloseable {
    private static final byte[] RAKNET_MAGIC = {
            0x00, (byte) 0xff, (byte) 0xff, 0x00, (byte) 0xfe, (byte) 0xfe, (byte) 0xfe, (byte) 0xfe,
            (byte) 0xfd, (byte) 0xfd, (byte) 0xfd, (byte) 0xfd, 0x12, 0x34, 0x56, 0x78
    };
    private final Map<String, BackendConfig> backends = new ConcurrentHashMap<>();
    private final Map<String, Health> health = new ConcurrentHashMap<>();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "onilink-dashboard-health");
        thread.setDaemon(true);
        return thread;
    });

    BackendHealthMonitor(java.util.Collection<BackendConfig> configuredBackends) {
        for (BackendConfig backend : configuredBackends) register(backend);
        executor.scheduleWithFixedDelay(this::probeAll, 0, 10, TimeUnit.SECONDS);
    }

    void register(BackendConfig backend) {
        backends.put(backend.name(), backend);
        health.put(backend.name(), Health.unknown());
    }

    void remove(String backend) {
        backends.remove(backend);
        health.remove(backend);
    }

    Health health(String backend) {
        return health.getOrDefault(backend, Health.unknown());
    }

    private void probeAll() {
        for (BackendConfig backend : backends.values()) {
            try {
                health.put(backend.name(), probe(backend));
            } catch (RuntimeException exception) {
                health.put(backend.name(), new Health(
                        "offline", -1, Instant.now().toString(), safeMessage(exception), 0, ""));
            }
        }
    }

    private static Health probe(BackendConfig backend) {
        long started = System.nanoTime();
        byte[] request = ByteBuffer.allocate(33).order(ByteOrder.BIG_ENDIAN)
                .put((byte) 0x01)
                .putLong(System.currentTimeMillis())
                .put(RAKNET_MAGIC)
                .putLong(ThreadLocalRandom.current().nextLong())
                .array();
        byte[] response = new byte[2_048];
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(1_000);
            socket.send(new DatagramPacket(request, request.length, backend.address()));
            DatagramPacket received = new DatagramPacket(response, response.length);
            socket.receive(received);
            if (received.getLength() < 33 || response[0] != 0x1c || !magicAt(response, 17)) {
                return new Health("degraded", elapsedMillis(started), Instant.now().toString(),
                        "Endpoint replied without a valid RakNet pong", 0, "");
            }
            String advertisement = advertisement(response, received.getLength());
            String[] fields = advertisement.split(";", -1);
            int protocol = fields.length > 2 ? integer(fields[2]) : 0;
            String version = fields.length > 3 ? fields[3] : "";
            return new Health("online", elapsedMillis(started), Instant.now().toString(), "RakNet pong received",
                    protocol, version);
        } catch (Exception exception) {
            return new Health("offline", elapsedMillis(started), Instant.now().toString(), safeMessage(exception), 0, "");
        }
    }

    private static String advertisement(byte[] response, int length) {
        if (length < 35) return "";
        int size = (Byte.toUnsignedInt(response[33]) << 8) | Byte.toUnsignedInt(response[34]);
        if (size < 1 || 35 + size > length) return "";
        return new String(response, 35, size, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static int integer(String value) {
        try { return Integer.parseInt(value); }
        catch (NumberFormatException ignored) { return 0; }
    }

    private static boolean magicAt(byte[] response, int offset) {
        for (int index = 0; index < RAKNET_MAGIC.length; index++) {
            if (response[offset + index] != RAKNET_MAGIC[index]) return false;
        }
        return true;
    }

    private static long elapsedMillis(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    record Health(
            String status, long latencyMillis, String checkedAt, String message,
            int advertisedProtocol, String advertisedVersion
    ) {
        static Health unknown() {
            return new Health("checking", -1, "", "Health check pending", 0, "");
        }

        Map<String, Object> asMap() {
            return Map.of(
                    "status", status,
                    "latencyMillis", latencyMillis,
                    "checkedAt", checkedAt,
                    "message", message,
                    "advertisedProtocol", advertisedProtocol,
                    "advertisedVersion", advertisedVersion
            );
        }
    }
}
