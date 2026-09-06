package dev.onistone.onilink.modules.operations;
import java.nio.file.*;
/** An actual child process used to test the process-manager contract without a Bedrock server. */
public final class ManagedProcessFixture {
    public static void main(String[] args) throws Exception {
        Path flag = Path.of("process-ready");
        switch (args[0]) {
            case "start" -> Files.writeString(flag, "ready");
            case "stop" -> Files.deleteIfExists(flag);
            case "health" -> System.exit(Files.exists(flag) ? 0 : 1);
            default -> System.exit(2);
        }
    }
}
