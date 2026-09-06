package dev.onistone.onilink.modules.forge;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ForgeServiceTest {
    @Test
    @SuppressWarnings("unchecked")
    void releaseDiffExposesTheScoreboardChangeHiddenByProtocolNumbers() {
        ForgeService forge = new ForgeService();
        assertEquals(List.of(), forge.diff(2168, 2169).get("changed"));
        Map<String, Object> diff = forge.diff("1.26.44", "1.26.45");
        List<Map<String, Object>> changed = (List<Map<String, Object>>) diff.get("changed");
        assertEquals(1, changed.size());
        assertEquals("SetScorePacket", changed.getFirst().get("packet"));
        assertEquals(true, changed.getFirst().get("serializerChanged"));
        assertEquals(false, diff.get("semanticEquivalenceInferred"));
        assertEquals(true, diff.get("translationEdge"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void matrixIncludesHotfixReencodingInBothDirections() {
        List<Map<String, Object>> rows = (List<Map<String, Object>>) new ForgeService().matrix().get("rows");
        for (String[] versions : new String[][]{{"1.26.40", "1.26.44"}, {"1.26.44", "1.26.40"},
                {"1.26.44", "1.26.45"}, {"1.26.45", "1.26.44"}}) {
            Map<String, Object> row = rows.stream().filter(r -> versions[0].equals(r.get("clientVersion"))
                    && versions[1].equals(r.get("backendVersion"))).findFirst().orElseThrow();
            assertEquals("SUPPORTED_WITH_LIMITS", row.get("status"));
            assertFalse(((List<String>) row.get("evidence")).contains("identity-codec"));
        }
    }

    @Test
    void automaticSelectionDoesNotInventAnExactReleaseDiff() {
        assertThrows(IllegalArgumentException.class, () -> new ForgeService().diff("auto", "1.26.45"));
    }
}
