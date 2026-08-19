package dev.onistone.onilink.backend;

import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;

/** StartGame changes that are safe only when the client and backend versions differ. */
record CrossProtocolStartGameFixups(
        long clearedBlockRegistryChecksum,
        boolean indexBasedBlockIds
) {

    static final CrossProtocolStartGameFixups NONE = new CrossProtocolStartGameFixups(0L, false);

    /** Disables a palette checksum that cannot match across different Minecraft versions. */
    static CrossProtocolStartGameFixups apply(StartGamePacket startGame, boolean crossProtocol) {
        if (!crossProtocol) {
            return NONE;
        }

        long cleared = startGame.getBlockRegistryChecksum();
        if (cleared != 0L) {
            startGame.setBlockRegistryChecksum(0L);
        }

        // Index-based block IDs require a palette remap; clearing the checksum is not enough.
        return new CrossProtocolStartGameFixups(cleared, !startGame.isBlockNetworkIdsHashed());
    }
}
