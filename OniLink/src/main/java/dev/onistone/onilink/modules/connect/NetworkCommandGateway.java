package dev.onistone.onilink.modules.connect;

import dev.onistone.onilink.platform.persistence.PlatformDatabase;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Carries the authenticated XUID and scope from the live command router to player services. */
public final class NetworkCommandGateway {
    @FunctionalInterface public interface Handler { List<String> execute(PlatformDatabase.Scope scope, String xuid, List<String> arguments); }
    private record Registration(Executor executor, Handler handler) { }
    private static volatile Registration registration;
    private NetworkCommandGateway() { }
    public static boolean available() { return registration != null; }
    public static void install(Executor executor, Handler handler) { registration = new Registration(executor, handler); }
    public static void uninstall(Handler handler) { var current = registration; if (current != null && current.handler() == handler) registration = null; }
    public static void submit(PlatformDatabase.Scope scope, String xuid, List<String> arguments, Consumer<String> response) {
        var current = registration;
        if (current == null) { response.accept("Network player services are disabled."); return; }
        try {
            current.executor().execute(() -> {
                try { current.handler().execute(scope, xuid, List.copyOf(arguments)).forEach(response); }
                catch (RuntimeException failure) { response.accept("Network request failed: " + failure.getMessage()); }
            });
        } catch (RejectedExecutionException busy) { response.accept("Network services are busy. Try again shortly."); }
    }
}
