package dev.onistone.onilink.control.wire;

import dev.onistone.onilink.config.OniControlConfig.ControlBackendConfig;
import dev.onistone.onilink.control.ActionStatus;
import dev.onistone.onilink.control.ActionType;

import javax.net.ssl.SSLSocket;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** One bounded, single-writer persistent ONICTL/1 connection for one backend. */
public final class ControlBridgeClient implements AutoCloseable {
    private static final long HEARTBEAT_MILLIS = 15_000;
    private final ControlBackendConfig config;
    private final byte[] secret;
    private final ArrayBlockingQueue<Job> jobs;
    private final AtomicReference<BridgeCapabilityDocument> capabilities = new AtomicReference<>();
    private final Thread worker;
    private volatile boolean closed;
    private volatile Socket socket;
    private volatile DataInputStream input;
    private volatile DataOutputStream output;
    private volatile long latencyMillis;
    private volatile String lastError = "not connected";
    private volatile Instant updatedAt = Instant.now();

    public ControlBridgeClient(ControlBackendConfig config) throws IOException {
        if (config == null || !config.enabled()) throw new IllegalArgumentException("enabled control backend is required");
        this.config = config;
        this.secret = ControlSecretLoader.load(config);
        this.jobs = new ArrayBlockingQueue<>(config.maxQueued());
        this.worker = new Thread(this::run, "onicontrol-" + config.backendName());
        this.worker.setDaemon(true);
        this.worker.start();
    }

    public CompletableFuture<ControlResponseEnvelope> request(
            ActionType action,
            String targetXuid,
            Map<String, Object> payload,
            String idempotencyKey,
            Instant deadline
    ) {
        if (action == null || deadline == null) throw new IllegalArgumentException("control action and deadline are required");
        BridgeCapabilityDocument capability = capabilities.get();
        if (capability == null || !capability.supports(action, 1)) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException(
                    capability == null ? "bridge capabilities are unavailable" : "active backend does not support " + action));
        }
        return enqueue(action.name(), targetXuid, payload, idempotencyKey, deadline);
    }

    public BridgeCapabilityDocument capabilities() {
        return capabilities.get();
    }

    public ControlBridgeStatus status() {
        BridgeCapabilityDocument capability = capabilities.get();
        return new ControlBridgeStatus(true, socket != null && socket.isConnected() && !socket.isClosed(),
                config.tls().enabled(), config.backendName(), config.bridgeId(),
                capability == null ? 0 : capability.revision(), latencyMillis, jobs.size(),
                capability == null ? 0 : capability.supportedActions().size(), lastError, updatedAt);
    }

    private CompletableFuture<ControlResponseEnvelope> enqueue(
            String action, String targetXuid, Map<String, Object> payload, String idempotencyKey, Instant deadline) {
        CompletableFuture<ControlResponseEnvelope> result = new CompletableFuture<>();
        if (closed) {
            result.completeExceptionally(new IOException("OniControl client is closed"));
            return result;
        }
        Job job = new Job(action, targetXuid, payload, idempotencyKey, deadline, result);
        if (!jobs.offer(job)) result.completeExceptionally(new IOException("OniControl queue is full"));
        return result;
    }

    private void run() {
        long reconnectDelay = 250;
        long lastActivity = 0;
        while (!closed) {
            Job active = null;
            try {
                active = jobs.poll(1, TimeUnit.SECONDS);
                if (active == null && socket == null) {
                    ensureConnected();
                    lastActivity = System.currentTimeMillis();
                    reconnectDelay = 250;
                    continue;
                }
                if (active == null && socket != null && System.currentTimeMillis() - lastActivity >= HEARTBEAT_MILLIS) {
                    exchange("PING", "0", Map.of(), UUID.randomUUID().toString(),
                            Instant.now().plusMillis(config.requestTimeoutMillis()));
                    lastActivity = System.currentTimeMillis();
                    continue;
                }
                if (active == null) continue;
                if (!active.deadline().isAfter(Instant.now())) {
                    active.result().completeExceptionally(new java.util.concurrent.TimeoutException("control request deadline expired"));
                    continue;
                }
                ControlResponseEnvelope response = exchange(
                        active.action(), active.targetXuid(), active.payload(), active.idempotencyKey(), active.deadline());
                active.result().complete(response);
                lastActivity = System.currentTimeMillis();
                reconnectDelay = 250;
            } catch (InterruptedException exception) {
                if (active != null) active.result().completeExceptionally(exception);
                if (!closed) Thread.currentThread().interrupt();
            } catch (Exception exception) {
                if (active != null) active.result().completeExceptionally(exception);
                failConnection(safeMessage(exception));
                long jitter = java.util.concurrent.ThreadLocalRandom.current().nextLong(Math.max(1, reconnectDelay / 3));
                try {
                    Thread.sleep(Math.min(30_000, reconnectDelay + jitter));
                } catch (InterruptedException interrupted) {
                    if (!closed) Thread.currentThread().interrupt();
                }
                reconnectDelay = Math.min(30_000, reconnectDelay * 2);
            }
        }
        Job job;
        while ((job = jobs.poll()) != null) job.result().completeExceptionally(new IOException("OniControl client stopped"));
    }

    private ControlResponseEnvelope exchange(
            String action, String targetXuid, Map<String, Object> payload, String idempotencyKey, Instant deadline)
            throws Exception {
        ensureConnected();
        String requestId = UUID.randomUUID().toString();
        ControlEnvelope request = ControlEnvelope.signed(config.keyId(), requestId, idempotencyKey,
                config.bridgeId(), config.backendName(), targetXuid, action, payload, secret);
        long remaining = Math.min(config.requestTimeoutMillis(), Math.max(1, deadline.toEpochMilli() - System.currentTimeMillis()));
        socket.setSoTimeout((int) remaining);
        long started = System.nanoTime();
        ControlFrameCodec.write(output, request.asMap(), config.maxFrameBytes());
        ControlResponseEnvelope response = ControlResponseEnvelope.parse(ControlFrameCodec.read(input, config.maxFrameBytes()));
        latencyMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        validate(response, requestId);
        updatedAt = Instant.now();
        lastError = "";
        return response;
    }

    private void ensureConnected() throws Exception {
        if (socket != null && socket.isConnected() && !socket.isClosed()) return;
        Socket connected;
        if (config.tls().enabled()) {
            connected = ControlTlsSockets.connect(config);
        } else {
            connected = new Socket();
            connected.connect(new InetSocketAddress(config.connectHost(), config.connectPort()), config.connectTimeoutMillis());
        }
        connected.setTcpNoDelay(true);
        connected.setKeepAlive(true);
        socket = connected;
        input = new DataInputStream(connected.getInputStream());
        output = new DataOutputStream(connected.getOutputStream());

        ControlResponseEnvelope response = exchangeWithoutConnect(
                "GET_CAPABILITIES", "0", Map.of(), UUID.randomUUID().toString());
        if (!ActionStatus.CONFIRMED.name().equals(response.status())) {
            throw new IOException("bridge capability negotiation was rejected: " + response.status());
        }
        BridgeCapabilityDocument document = BridgeCapabilityDocument.parse(
                response.decodedPayload(config.maxFrameBytes()));
        if (!document.backend().equals(config.backendName()) || !document.bridgeId().equals(config.bridgeId())) {
            throw new IOException("bridge capability scope does not match configured backend");
        }
        if (document.tlsActive() != config.tls().enabled()) {
            throw new IOException("bridge capability TLS state does not match the transport");
        }
        capabilities.set(document);
    }

    private ControlResponseEnvelope exchangeWithoutConnect(
            String action, String targetXuid, Map<String, Object> payload, String idempotencyKey) throws Exception {
        String requestId = UUID.randomUUID().toString();
        ControlEnvelope request = ControlEnvelope.signed(config.keyId(), requestId, idempotencyKey,
                config.bridgeId(), config.backendName(), targetXuid, action, payload, secret);
        socket.setSoTimeout(config.requestTimeoutMillis());
        ControlFrameCodec.write(output, request.asMap(), config.maxFrameBytes());
        ControlResponseEnvelope response = ControlResponseEnvelope.parse(ControlFrameCodec.read(input, config.maxFrameBytes()));
        validate(response, requestId);
        return response;
    }

    private void validate(ControlResponseEnvelope response, String requestId) throws IOException {
        long skew = Math.abs(System.currentTimeMillis() - response.timestamp());
        if (!response.keyId().equals(config.keyId()) || !response.requestId().equals(requestId)
                || !response.bridgeId().equals(config.bridgeId()) || !response.backend().equals(config.backendName())) {
            throw new IOException("ONICTL response scope or request identity mismatch");
        }
        if (skew > TimeUnit.SECONDS.toMillis(config.maxClockSkewSeconds())) {
            throw new IOException("ONICTL response timestamp is stale");
        }
        if (!ControlSigner.verifyResponse(response, secret)) throw new IOException("ONICTL response signature is invalid");
    }

    private void failConnection(String message) {
        lastError = message;
        updatedAt = Instant.now();
        capabilities.set(null);
        closeQuietly(input);
        closeQuietly(output);
        closeQuietly(socket);
        input = null;
        output = null;
        socket = null;
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (IOException ignored) {
            // Connection cleanup cannot affect player forwarding.
        }
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    @Override
    public void close() {
        closed = true;
        worker.interrupt();
        failConnection("stopped");
        Arrays.fill(secret, (byte) 0);
    }

    private record Job(
            String action,
            String targetXuid,
            Map<String, Object> payload,
            String idempotencyKey,
            Instant deadline,
            CompletableFuture<ControlResponseEnvelope> result
    ) {
    }
}
