package dev.onistone.onilink.backend;

import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;

/** Client-facing StartGame adjustments that are independent of protocol translation. */
record StartGameClientFixups(boolean enabledCommands) {
    private static final boolean DISABLED = Boolean.getBoolean("proxy.noStartGameFixups");

    static StartGameClientFixups apply(StartGamePacket startGame) {
        if (DISABLED) {
            return new StartGameClientFixups(false);
        }

        // Keep the backend's death-system and default-permission fields unchanged. Proxy commands
        // are advertised at CommandPermission.ANY and do not require either field to be overridden.

        boolean enabledCommands = !startGame.isCommandsEnabled();
        if (enabledCommands) {
            startGame.setCommandsEnabled(true);
        }

        return new StartGameClientFixups(enabledCommands);
    }
}
