package dev.onistone.onilink.plugin;

import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import dev.onistone.onilink.config.ProxyConfig;
import dev.onistone.onilink.protocol.CanonicalProtocol;
import dev.onistone.onilink.protocol.PacketTranslator;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.jar.JarFile;

/**
 * Finds, enables and shuts down the addons in {@code plugins/}.
 *
 * <p>An addon is any jar with an {@code onilink-plugin.properties} at its root naming a {@code name}
 * and a {@code main}. API 1 descriptors declare versions, exact dependencies and API permissions.
 * Existing descriptors retain the legacy trusted API capabilities. This is what makes
 * "{@code java -jar OniLink.jar} with an empty {@code plugins/}" a plain Bedrock proxy rather than a
 * proxy with its Java support switched off.</p>
 *
 * <p><b>Failures are contained.</b> A jar that cannot be read, an addon whose class will not load, or
 * one that throws while enabling is reported and skipped. Bedrock players must not lose their server
 * because an optional addon is broken, which is the same reason the bridge has always been
 * allowed to fail without taking the proxy with it.</p>
 */
public final class PluginManager {
    private static final String DESCRIPTOR = "onilink-plugin.properties";

    private final Path pluginsDirectory;
    private final ProxyConfig proxyConfig;
    private final List<LoadedPlugin> plugins = new ArrayList<>();
    private final List<ProtocolUpgrade> protocolUpgrades = new ArrayList<>();
    private final List<TrustedListenerSpec> trustedListeners = new ArrayList<>();

    public record ProtocolUpgrade(CanonicalProtocol older, CanonicalProtocol newer, PacketTranslator translator) {
    }

    private record LoadedPlugin(String name, String version, OniLinkPlugin plugin, URLClassLoader classLoader, Context context) {
    }

    public PluginManager(Path pluginsDirectory, ProxyConfig proxyConfig) {
        this.pluginsDirectory = pluginsDirectory;
        this.proxyConfig = proxyConfig;
    }

    public List<ProtocolUpgrade> protocolUpgrades() {
        return List.copyOf(protocolUpgrades);
    }

    public List<TrustedListenerSpec> trustedListeners() {
        return List.copyOf(trustedListeners);
    }

    /** Discovers and enables every addon. Contributions are collected but nothing is bound yet. */
    public void enableAll() {
        if (pluginsDirectory == null) {
            return;
        }
        // Create it even when there is nothing to load. An operator who has been told "drop the addon
        // in plugins/" should find the folder already there, exactly as they would on Paper or
        // Velocity — being asked to create it themselves invites creating it in the wrong place.
        if (!Files.isDirectory(pluginsDirectory)) {
            try {
                Files.createDirectories(pluginsDirectory);
            } catch (IOException exception) {
                System.out.printf("Could not create the plugins directory %s: %s. Addons will not load.%n",
                        pluginsDirectory, exception);
                return;
            }
        }
        List<Path> jars = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(pluginsDirectory, "*.jar")) {
            entries.forEach(jars::add);
        } catch (IOException exception) {
            System.out.printf("Could not read the plugins directory %s: %s.%n", pluginsDirectory, exception);
            return;
        }
        jars.sort(Path::compareTo);

        for (Path jar : order(jars)) {
            try {
                load(jar);
            } catch (Throwable throwable) {
                System.out.printf("Addon %s failed to load and was skipped: %s.%n", jar.getFileName(), throwable);
            }
        }
    }

    private void load(Path jar) throws Exception {
        String name;
        String mainClass;
        Properties descriptor = new Properties();
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            var entry = jarFile.getEntry(DESCRIPTOR);
            if (entry == null) {
                // Not an addon. Someone's unrelated jar in the folder is not an error.
                return;
            }
            try (InputStream input = jarFile.getInputStream(entry)) {
                descriptor.load(input);
            }
            name = descriptor.getProperty("name", "").trim();
            mainClass = descriptor.getProperty("main", "").trim();
        }
        if (name.isEmpty() || mainClass.isEmpty()) {
            System.out.printf("Addon %s has an %s without both 'name' and 'main'; skipped.%n",
                    jar.getFileName(), DESCRIPTOR);
            return;
        }
        if (!name.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")) throw new IllegalArgumentException("addon name must be a safe directory name");
        if (plugins.stream().anyMatch(plugin -> plugin.name().equalsIgnoreCase(name))) throw new IllegalArgumentException("duplicate addon name");
        String api = descriptor.getProperty("api-version", "1");
        if (!api.equals("1")) throw new IllegalArgumentException("unsupported addon API version " + api);
        for (var dependency : dependencies(descriptor).entrySet()) {
            if (plugins.stream().noneMatch(p -> p.name().equals(dependency.getKey()) && p.version().equals(dependency.getValue())))
                throw new IllegalArgumentException("missing or incompatible addon dependency " + dependency.getKey());
        }

        // Parent is this classloader on purpose: an addon is written against the proxy's API and has
        // to see it. The proxy never sees the addon, which is the direction that matters.
        URLClassLoader classLoader = new URLClassLoader(
                name,
                new URL[]{jar.toUri().toURL()},
                PluginManager.class.getClassLoader()
        );
        int upgradeCount = protocolUpgrades.size();
        int listenerCount = trustedListeners.size();
        Context context = null;
        OniLinkPlugin enablingPlugin = null;
        boolean enabled = false;
        try {
        Object instance = Class.forName(mainClass, true, classLoader).getDeclaredConstructor().newInstance();
        if (!(instance instanceof OniLinkPlugin plugin)) {
            classLoader.close();
            System.out.printf("Addon %s: %s does not implement OniLinkPlugin; skipped.%n", name, mainClass);
            return;
        }
        enablingPlugin = plugin;

        Path dataFolder = pluginsDirectory.resolve(name);
        Files.createDirectories(dataFolder);
        if (!dataFolder.toRealPath().startsWith(pluginsDirectory.toRealPath()) || Files.isSymbolicLink(dataFolder)) throw new IOException("addon data folder escapes plugins directory");
        java.util.Set<String> permissions = descriptor.containsKey("api-version")
                ? java.util.Arrays.stream(descriptor.getProperty("permissions", "").split(",")).map(String::trim).filter(s -> !s.isBlank()).collect(java.util.stream.Collectors.toSet())
                : java.util.Set.of("protocol.contribute", "listeners.bind", "events.subscribe");
        context = new Context(name, dataFolder, permissions);
        plugin.onEnable(context);
        plugins.add(new LoadedPlugin(name, descriptor.getProperty("version", "legacy"), plugin, classLoader, context));
        enabled = true;
        System.out.printf("Enabled addon %s.%n", name);
        } finally {
            if (!enabled) {
                if (enablingPlugin != null) try { enablingPlugin.onDisable(); } catch (Throwable ignored) { }
                protocolUpgrades.subList(upgradeCount, protocolUpgrades.size()).clear();
                trustedListeners.subList(listenerCount, trustedListeners.size()).clear();
                if (context != null) context.close();
                classLoader.close();
            }
        }
    }

    private List<Path> order(List<Path> jars) {
        java.util.Map<String, Path> pending = new java.util.LinkedHashMap<>();
        java.util.Map<String, Properties> descriptors = new java.util.HashMap<>();
        for (Path path : jars) try (JarFile jar = new JarFile(path.toFile())) {
            var entry = jar.getJarEntry(DESCRIPTOR);
            if (entry == null || entry.getSize() > 65536) continue;
            Properties descriptor = new Properties();
            try (InputStream input = jar.getInputStream(entry)) { descriptor.load(input); }
            String name = descriptor.getProperty("name", "");
            if (pending.putIfAbsent(name, path) != null) throw new IllegalArgumentException("duplicate addon " + name);
            descriptors.put(name, descriptor);
        } catch (Exception failure) { System.err.println("Could not inspect addon " + path.getFileName() + ": " + failure.getMessage()); }
        List<Path> sorted = new ArrayList<>();
        java.util.Set<String> resolved = new java.util.HashSet<>();
        while (!pending.isEmpty()) {
            String next = pending.keySet().stream().filter(name -> {
                try { return resolved.containsAll(dependencies(descriptors.get(name)).keySet()); }
                catch (IllegalArgumentException failure) { return false; }
            }).findFirst().orElse(null);
            if (next == null) { System.err.println("Skipped addons with missing or cyclic dependencies: " + pending.keySet()); break; }
            sorted.add(pending.remove(next)); resolved.add(next);
        }
        return sorted;
    }

    private static java.util.Map<String, String> dependencies(Properties descriptor) {
        java.util.Map<String, String> result = new java.util.LinkedHashMap<>();
        for (String dependency : descriptor.getProperty("depends", "").split(",")) {
            if (dependency.isBlank()) continue;
            String[] pair = dependency.trim().split("@", -1);
            if (pair.length != 2 || pair[0].isBlank() || pair[1].isBlank()) throw new IllegalArgumentException("dependencies require name@version");
            result.put(pair[0], pair[1]);
        }
        return result;
    }

    /** Tells every enabled addon that the listeners are up. */
    public void proxyReady() {
        for (LoadedPlugin loaded : plugins) {
            try {
                loaded.plugin().onProxyReady();
            } catch (Throwable throwable) {
                System.out.printf("Addon %s failed to start: %s.%n", loaded.name(), throwable);
            }
        }
    }

    /** Disables addons in reverse enable order. Never throws. */
    public void disableAll() {
        for (int i = plugins.size() - 1; i >= 0; i--) {
            LoadedPlugin loaded = plugins.get(i);
            try {
                loaded.plugin().onDisable();
            } catch (Throwable ignored) {
                // Shutting down; a misbehaving addon must not stop the rest from shutting down.
            }
            try {
                loaded.classLoader().close();
            } catch (IOException ignored) {
                // as above
            }
            loaded.context().close();
        }
        plugins.clear();
        protocolUpgrades.clear();
        trustedListeners.clear();
    }

    public boolean isEmpty() {
        return plugins.isEmpty();
    }

    private final class Context implements PluginContext {
        private final String name;
        private final Path dataFolder;
        private final java.util.Set<String> permissions;
        private final List<AutoCloseable> subscriptions = new ArrayList<>();

        private Context(String name, Path dataFolder, java.util.Set<String> permissions) {
            this.name = name;
            this.dataFolder = dataFolder;
            this.permissions = java.util.Set.copyOf(permissions);
        }

        private void require(String permission) { if (!permissions.contains(permission)) throw new SecurityException("addon did not declare " + permission); }
        @Override public AutoCloseable subscribe(String tenant, String proxy, dev.onistone.onilink.platform.events.OniEventType type,
                java.util.function.Consumer<dev.onistone.onilink.platform.events.OniEvent> listener) {
            require("events.subscribe");
            AutoCloseable subscription = AddonEvents.subscribe(tenant, proxy, type, listener);
            subscriptions.add(subscription); return subscription;
        }
        void close() { subscriptions.forEach(s -> { try { s.close(); } catch (Exception ignored) { } }); subscriptions.clear(); }

        @Override
        public Path dataFolder() {
            return dataFolder;
        }

        @Override
        public ProxyConfig proxyConfig() {
            return proxyConfig;
        }

        @Override
        public void info(String message) {
            System.out.printf("[%s] %s%n", name, message);
        }

        @Override
        public void addProtocolUpgrade(CanonicalProtocol older, CanonicalProtocol newer, PacketTranslator translator) {
            require("protocol.contribute");
            protocolUpgrades.add(new ProtocolUpgrade(older, newer, translator));
        }

        @Override
        public void addTrustedListener(TrustedListenerSpec spec) {
            require("listeners.bind");
            trustedListeners.add(spec);
        }
    }

    /** Exposed so the listener can describe what it is about to bind. */
    public static BedrockCodec advertisedCodecOf(TrustedListenerSpec spec) {
        return spec.advertisedCodec();
    }
}
