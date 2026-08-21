package dev.onistone.onilink.modules.forge;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Prevents documentation from promoting a native action beyond the checked-in capability source. */
class CompatibilityDocumentationTest {
    private static final Pattern ACTION = Pattern.compile("`([A-Z][A-Z0-9_]+)`");

    @Test
    void supportedNativeClaimsHaveCheckedInCapabilityEvidence() throws Exception {
        Path root = Path.of("..").toAbsolutePath().normalize();
        String nativeSource = Files.readString(root.resolve("OniBridge/src/plugin/onibridge_plugin.cpp"));
        int start = nativeSource.indexOf("supportedControlActions() const");
        int end = nativeSource.indexOf("void dispatchControl", start);
        assertTrue(start >= 0 && end > start, "native capability manifest is missing");
        String manifest = nativeSource.substring(start, end);

        String documentation = Files.readString(root.resolve("docs/COMPATIBILITY.md"));
        Set<String> nativeActions = Set.of(
                "PING", "GET_CAPABILITIES", "GET_BACKEND_HEALTH", "GET_ONLINE_PLAYERS",
                "GET_PLAYER_STATE", "PREPARE_DRAIN", "CLOSE_PLAYER_CONTAINERS", "SAVE_WORLD");
        for (String line : documentation.lines().toList()) {
            if (!line.contains("`SUPPORTED") && !line.contains("`CANDIDATE")) continue;
            Matcher matcher = ACTION.matcher(line);
            while (matcher.find()) {
                String action = matcher.group(1);
                if (nativeActions.contains(action)) {
                    assertTrue(manifest.contains('"' + action + '"'),
                            () -> "documentation promotes native action without manifest evidence: " + action);
                }
            }
        }
        assertFalse(manifest.contains("EXECUTE_COMMAND"),
                "the approved initial native bridge must not advertise unrestricted commands");
    }
}
