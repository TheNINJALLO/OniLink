package dev.onistone.onilink.modules.operations;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.*;

/** Caps response bytes before buffering and exposes a cancellable full-response future. */
public final class BoundedHttpBody implements HttpResponse.BodySubscriber<byte[]> {
    private final int maximum;
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final CompletableFuture<byte[]> result = new CompletableFuture<>();
    private Flow.Subscription subscription;
    public BoundedHttpBody(int maximum) { if (maximum < 1) throw new IllegalArgumentException("positive response limit required"); this.maximum = maximum; }
    public CompletionStage<byte[]> getBody() { return result; }
    public void onSubscribe(Flow.Subscription subscription) { this.subscription = subscription; subscription.request(1); }
    public void onNext(List<ByteBuffer> buffers) {
        for (var buffer : buffers) {
            if (buffer.remaining() > maximum - bytes.size()) { subscription.cancel(); result.completeExceptionally(new java.io.IOException("HTTP response exceeds limit")); return; }
            byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
        }
        subscription.request(1);
    }
    public void onError(Throwable error) { result.completeExceptionally(error); }
    public void onComplete() { result.complete(bytes.toByteArray()); }
    public static HttpResponse<byte[]> send(java.net.http.HttpClient client, java.net.http.HttpRequest request, int maximum, long seconds) throws Exception {
        var future = client.sendAsync(request, ignored -> new BoundedHttpBody(maximum));
        try { return future.get(seconds, TimeUnit.SECONDS); }
        catch (Exception failure) { future.cancel(true); throw failure; }
    }
}
