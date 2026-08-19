package dev.onistone.onilink.backend;

import org.cloudburstmc.protocol.bedrock.data.PlayerPermission;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests the StartGame fields OniLink owns without rewriting backend-owned permissions. */
class StartGameClientFixupsTest {

    private static StartGamePacket backendStartGame() {
        StartGamePacket startGame = new StartGamePacket();
        startGame.setTickDeathSystemsEnabled(false);
        startGame.setCommandsEnabled(false);
        startGame.setDefaultPlayerPermission(PlayerPermission.MEMBER);
        return startGame;
    }

    @Test
    void leavesTickDeathSystemsAloneBecauseTheBackendsValueIsKnownGood() {
        StartGamePacket startGame = backendStartGame();

        StartGameClientFixups.apply(startGame);

        assertFalse(startGame.isTickDeathSystemsEnabled(), "must relay the backend's value unchanged");
    }

    @Test
    void enablesCommandsSoInjectedProxyCommandsAreVisible() {
        StartGamePacket startGame = backendStartGame();

        StartGameClientFixups fixups = StartGameClientFixups.apply(startGame);

        assertTrue(startGame.isCommandsEnabled());
        assertTrue(fixups.enabledCommands());
    }

    @Test
    void relaysTheBackendsDefaultPlayerPermissionUnchanged() {
        StartGamePacket startGame = backendStartGame();

        StartGameClientFixups.apply(startGame);

        assertEquals(PlayerPermission.MEMBER, startGame.getDefaultPlayerPermission());
    }

    @Test
    void leavesAnOperatorBackendPermissionAloneToo() {
        StartGamePacket startGame = backendStartGame();
        startGame.setDefaultPlayerPermission(PlayerPermission.OPERATOR);

        StartGameClientFixups.apply(startGame);

        assertEquals(PlayerPermission.OPERATOR, startGame.getDefaultPlayerPermission());
    }

    @Test
    void reportsNothingChangedWhenTheBackendAlreadyAgrees() {
        StartGamePacket startGame = new StartGamePacket();
        startGame.setCommandsEnabled(true);

        StartGameClientFixups fixups = StartGameClientFixups.apply(startGame);

        assertFalse(fixups.enabledCommands());
        assertTrue(startGame.isCommandsEnabled());
    }

    @Test
    void isIdempotentAcrossRepeatedBackendSwitches() {
        StartGamePacket startGame = backendStartGame();

        StartGameClientFixups.apply(startGame);
        StartGameClientFixups second = StartGameClientFixups.apply(startGame);

        assertFalse(second.enabledCommands());
        assertTrue(startGame.isCommandsEnabled());
        assertEquals(PlayerPermission.MEMBER, startGame.getDefaultPlayerPermission());
    }
}
