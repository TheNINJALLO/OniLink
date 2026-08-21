package dev.onistone.onilink.modules.connect;

import dev.onistone.onilink.platform.persistence.PlatformDatabase;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Async boundary between the packet command router and the durable support service. */
public final class SupportCommandGateway {
    @FunctionalInterface
    public interface Handler {
        List<String> execute(
                PlatformDatabase.Scope scope, String xuid, String displayLabel, String backend,
                List<String> arguments);
    }

    private static volatile Registration registration;

    private SupportCommandGateway() {}

    public static void install(Executor executor, Handler handler) {
        registration = new Registration(executor, handler);
    }

    public static void uninstall(Handler handler) {
        Registration current = registration;
        if (current != null && current.handler() == handler) registration = null;
    }

    public static void submit(
            PlatformDatabase.Scope scope, String xuid, String displayLabel, String backend,
            List<String> arguments, Consumer<String> response
    ) {
        Registration current = registration;
        if (current == null) {
            response.accept("The OniLink support service is disabled or unavailable.");
            return;
        }
        current.executor().execute(() -> {
            try {
                for (String line : current.handler().execute(scope, xuid, displayLabel, backend, arguments)) {
                    response.accept(line);
                }
            } catch (RuntimeException failure) {
                String message = failure.getMessage();
                response.accept("Support request failed: " + (message == null ? "internal error" : message));
            }
        });
    }

    private record Registration(Executor executor, Handler handler) {}
}
