package dev.onistone.onilink.protocol;

import org.cloudburstmc.protocol.bedrock.packet.ServerboundPackSettingChangePacket;
import org.cloudburstmc.protocol.bedrock.packet.UnknownPacket;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class ModernClientTo2168TranslatorTest {
    @Test
    void dropsPacketsThatDoNotExistOn2168() {
        UnknownPacket furnaceOptions = new UnknownPacket();
        furnaceOptions.setPacketId(351);
        UnknownPacket recordStarted = new UnknownPacket();
        recordStarted.setPacketId(352);

        assertNull(ModernClientTo2168Translator.INSTANCE.translateServerbound(furnaceOptions, null));
        assertNull(ModernClientTo2168Translator.INSTANCE.translateClientbound(recordStarted, null));
    }

    @Test
    void dropsOnlyTheNewStringListPackSettingArm() {
        ServerboundPackSettingChangePacket listSetting = new ServerboundPackSettingChangePacket();
        listSetting.setPackSettingValue(List.of("one", "two"));
        assertNull(ModernClientTo2168Translator.INSTANCE.translateServerbound(listSetting, null));

        ServerboundPackSettingChangePacket stringSetting = new ServerboundPackSettingChangePacket();
        stringSetting.setPackSettingValue("one");
        assertSame(stringSetting,
                ModernClientTo2168Translator.INSTANCE.translateServerbound(stringSetting, null));
    }
}
