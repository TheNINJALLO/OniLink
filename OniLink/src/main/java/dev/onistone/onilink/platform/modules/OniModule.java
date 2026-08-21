package dev.onistone.onilink.platform.modules;

import java.util.Set;

public interface OniModule extends AutoCloseable {
    String id();

    Set<String> dependencies();

    boolean enabled();

    void initialize(ModuleContext context);

    void start();

    ModuleHealth health();

    ModuleCapabilities capabilities();

    @Override
    void close();
}
