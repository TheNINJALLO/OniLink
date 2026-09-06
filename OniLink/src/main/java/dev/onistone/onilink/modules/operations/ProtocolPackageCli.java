package dev.onistone.onilink.modules.operations;

import dev.onistone.onilink.control.ControlJson;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.*;

/** Offline publisher tooling. Private key material is written only to the explicitly named file. */
public final class ProtocolPackageCli {
    private ProtocolPackageCli() { }
    public static void main(String[] args) throws Exception {
        if (args.length == 4 && args[0].equals("keygen")) {
            if (!args[1].matches("[a-z0-9][a-z0-9_-]{0,63}")) throw new IllegalArgumentException("invalid publisher ID");
            var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            Path privateFile = Path.of(args[2]); Path publicFile = Path.of(args[3]);
            if (Files.exists(privateFile) || Files.exists(publicFile)) throw new IllegalArgumentException("key output files must not already exist");
            try { Files.createFile(privateFile, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))); }
            catch (UnsupportedOperationException windows) { Files.createFile(privateFile); }
            Files.write(privateFile, pair.getPrivate().getEncoded());
            Files.writeString(publicFile, args[1] + "=" + Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()) + "\n", StandardOpenOption.CREATE_NEW);
            System.out.println("Created publisher key files. Keep the private key outside server uploads and source control.");
        } else if (args.length == 4 && args[0].equals("sign")) {
            Path keyFile = Path.of(args[1]);
            if (Files.size(keyFile) > 4096) throw new IllegalArgumentException("private key file is too large");
            var key = KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(keyFile)));
            String sha = ArtifactStore.sha256(Path.of(args[2]));
            Signature signature = Signature.getInstance("Ed25519"); signature.initSign(key);
            signature.update(("OniLink-protocol-v1\n" + sha + "\n").getBytes(StandardCharsets.US_ASCII));
            Files.writeString(Path.of(args[3]), Base64.getEncoder().encodeToString(signature.sign()) + "\n", StandardOpenOption.CREATE_NEW);
            System.out.println("Signed protocol artifact " + sha);
        } else if (args.length == 4 && args[0].equals("inspect")) {
            Path path = Path.of(args[3]);
            var result = new LinkedHashMap<>(ArtifactStore.inspect(path, args[1], args[2]));
            result.put("sha256", ArtifactStore.sha256(path)); result.put("bytes", Files.size(path));
            System.out.println(ControlJson.encode(result));
        } else throw new IllegalArgumentException("Usage: keygen <publisher> <private.pk8> <public.properties> | sign <private.pk8> <package.jar> <signature.txt> | inspect <kind> <platform> <archive>");
    }
}
