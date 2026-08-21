package dev.onistone.onilink.modules.forge;

import dev.onistone.onilink.control.ControlJson;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** CI entry point for reproducible JSON and Markdown compatibility artifacts. */
public final class ForgeCli {
    private ForgeCli() {}

    public static void main(String[] args) throws Exception {
        Path output = args.length == 0 ? Path.of("build", "forge") : Path.of(args[0]);
        Files.createDirectories(output);
        ForgeService forge = new ForgeService();
        Files.writeString(output.resolve("compatibility-matrix.json"),
                ControlJson.encode(forge.matrix()) + "\n", StandardCharsets.UTF_8);
        Files.writeString(output.resolve("compatibility-matrix.md"),
                forge.matrixMarkdown(), StandardCharsets.UTF_8);
    }
}
