package dev.onistone.onilink.dashboard;

import dev.onistone.onilink.config.BackendConfig;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.util.Collection;
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
    private final Collection<BackendConfig> backends;
    private final Map<String, Health> health = new ConcurrentHashMap<>();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "onilink-dashboard-health");
        thread.setDaemon(true);
        return thread;
    });

    BackendHealthMonitor(Collection<BackendConfig> backends) {
        this.backends = backends;
        for (BackendConfig backend : backends) health.put(backend.name(), Health.unknown());
        executor.scheduleWithFixedDelay(this::probeAll, 0, 10, TimeUnit.SECONDS);
    }

    Health health(String backend) {
        return health.getOrDefault(backend, Health.unknown());
    }

    private void probeAll() {
        for (BackendConfig backend : backends) {
            try {
                health.put(backend.name(), probe(backend));
            } catch (RuntimeException exception) {
                health.put(backend.name(), new Health(
                        "offline", -1, Instant.now().toString(), safeMessage(exception)));
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
                        "Endpoint replied without a valid RakNet pong");
            }
            return new Health("online", elapsedMillis(started), Instant.now().toString(), "RakNet pong received");
        } catch (Exception exception) {
            return new Health("offline", elapsedMillis(started), Instant.now().toString(), safeMessage(exception));
        }
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

    record Health(String status, long latencyMillis, String checkedAt, String message) {
        static Health unknown() {
            return new Health("checking", -1, "", "Health check pending");
        }

        Map<String, Object> asMap() {
            return Map.of(
                    "status", status,
                    "latencyMillis", latencyMillis,
                    "checkedAt", checkedAt,
                    "message", message
            );
        }
    }
}
