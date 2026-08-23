package dev.onistone.onilink.allowlist;

import dev.onistone.onilink.config.AllowlistConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AllowlistImporterTest {
    @Test
    void parsesJsonCsvPropertiesAndReportsNameOnlyRows() {
        AllowlistImporter.ParseResult json = AllowlistImporter.parse("""
                {"entries":[
                  {"xuid":"2533274790000001","name":"ExamplePlayer"},
                  {"name":"MissingIdentity"}
                ]}
                """);
        assertEquals(1, json.entries().size());
        assertEquals(1, json.rejected());
        assertTrue(json.errors().getFirst().contains("name-only"));

        AllowlistImporter.ParseResult csv = AllowlistImporter.parse("""
                gamertag,xuid
                Builder,2533274790000002
                "Quoted Player",2533274790000003
                """);
        assertEquals(2, csv.entries().size());
        assertEquals("Builder", csv.entries().getFirst().name());

        AllowlistImporter.ParseResult properties = AllowlistImporter.parse("""
                # OniLink allowlist
                2533274790000004=SurvivalPlayer
                2533274790000005=
                """);
        assertEquals(2, properties.entries().size());
        assertEquals("SurvivalPlayer", properties.entries().getFirst().name());

        assertThrows(IllegalArgumentException.class,
                () -> AllowlistImporter.parse("[{\"name\":\"NameOnly\"}]"));
    }

    @Test
    void bulkImportMergesOrReplacesInOnePersistentUpdate(@TempDir Path directory) throws Exception {
        AllowlistConfig config = new AllowlistConfig(
                true, directory.resolve("allowlist.properties"), "Not allowed", true);
        ProxyAllowlist allowlist = ProxyAllowlist.load(config);
        allowlist.add("2533274790000001", "Original");

        ProxyAllowlist.ImportSummary merged = allowlist.importEntries(
                AllowlistImporter.parse("xuid,name\n2533274790000001,Updated\n2533274790000002,New").entries(),
                false);
        assertEquals(1, merged.added());
        assertEquals(1, merged.updated());
        assertEquals(2, ProxyAllowlist.load(config).entries().size());

        ProxyAllowlist.ImportSummary replaced = allowlist.importEntries(
                AllowlistImporter.parse("2533274790000002=Only").entries(), true);
        assertEquals(1, replaced.removed());
        assertEquals(1, replaced.total());
        assertEquals("2533274790000002", ProxyAllowlist.load(config).entries().getFirst().xuid());
    }
}
