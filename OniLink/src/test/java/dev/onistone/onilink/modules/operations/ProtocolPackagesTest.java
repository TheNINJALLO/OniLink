package dev.onistone.onilink.modules.operations;

import dev.onistone.onilink.control.ControlJson;
import dev.onistone.onilink.modules.FakeProxy;
import dev.onistone.onilink.platform.persistence.PlatformDatabase;
import dev.onistone.onilink.plugin.ProtocolPackage;
import dev.onistone.onilink.protocol.ProtocolFixtureSuites;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.ToolProvider;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ProtocolPackagesTest {
    @TempDir Path directory;
    @Test void signatureDependencyValidationActivationRollbackAndRestartKeepSnapshots() throws Exception {
        Path source = directory.resolve("GeneratedProtocol.java");
        Files.writeString(source, "public final class GeneratedProtocol implements dev.onistone.onilink.plugin.ProtocolPackage { public void contribute(dev.onistone.onilink.protocol.ProtocolRegistry.Builder registry) {} }");
        String classpath = Path.of(ProtocolPackage.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-classpath", classpath, "-d", directory.toString(), source.toString()));
        var manifest = Map.of("apiVersion", 1, "packageId", "test-protocol", "version", "1.0.0", "provider", "GeneratedProtocol", "dependencies", Map.of());
        byte[] archive = ArtifactStoreTest.zip(Map.of("GeneratedProtocol.class", Files.readAllBytes(directory.resolve("GeneratedProtocol.class")),
                "onilink-protocol.json", ControlJson.encode(manifest).getBytes(StandardCharsets.UTF_8), "fixtures.json", ControlJson.encode(ProtocolFixtureSuites.suite()).getBytes(StandardCharsets.UTF_8)));
        var key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var proxy = new FakeProxy(); var original = proxy.protocols();
        try (var db = new PlatformDatabase(directory.resolve("data"))) {
            var artifacts = new ArtifactStore(db, directory, 1048576, 4194304);
            String id = String.valueOf(artifacts.upload(ArtifactStoreTest.SCOPE, "protocol", "1.0.0", "any", new ByteArrayInputStream(archive)).get("id"));
            try (var packages = new ProtocolPackages(db, artifacts, proxy, Map.of("test", key.getPublic()))) {
                String signature = sign(key, id);
                assertThrows(GeneralSecurityException.class, () -> packages.trust(ArtifactStoreTest.SCOPE, id, "unknown", signature));
                assertThrows(GeneralSecurityException.class, () -> packages.trust(ArtifactStoreTest.SCOPE, id, "test", Base64.getEncoder().encodeToString(new byte[64])));
                packages.trust(ArtifactStoreTest.SCOPE, id, "test", signature);
                packages.activate(ArtifactStoreTest.SCOPE, List.of(id), 0);
                var active = proxy.protocols();
                assertNotSame(original, active);
                assertThrows(IllegalStateException.class, () -> packages.activate(ArtifactStoreTest.SCOPE, List.of(), 0));
                packages.activate(ArtifactStoreTest.SCOPE, List.of(), 1);
                assertNotSame(active, proxy.protocols());
                assertTrue(active.findBinding(2169, 2168).isPresent(), "retired session registry stays usable");
            }
            var restartedProxy = new FakeProxy();
            try (var packages = new ProtocolPackages(db, artifacts, restartedProxy, Map.of("test", key.getPublic()))) {
                packages.restore(ArtifactStoreTest.SCOPE);
                assertEquals(2, db.get(ArtifactStoreTest.SCOPE, "protocol-generation", "current").orElseThrow().revision());
            }
        }
    }
    private static String sign(KeyPair key, String sha) throws Exception {
        Signature signature = Signature.getInstance("Ed25519"); signature.initSign(key.getPrivate());
        signature.update(("OniLink-protocol-v1\n" + sha + "\n").getBytes(StandardCharsets.US_ASCII));
        return Base64.getEncoder().encodeToString(signature.sign());
    }
}
