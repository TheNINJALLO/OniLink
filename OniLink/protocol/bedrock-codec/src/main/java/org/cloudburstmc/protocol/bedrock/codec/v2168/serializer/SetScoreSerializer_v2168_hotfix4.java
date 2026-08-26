package org.cloudburstmc.protocol.bedrock.codec.v2168.serializer;

import io.netty.buffer.ByteBuf;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.data.ScoreInfo;
import org.cloudburstmc.protocol.bedrock.packet.SetScorePacket;
import org.cloudburstmc.protocol.common.util.VarInts;

/** 1.26.44's unversioned change to protocol 2168's remove-score entry. */
public final class SetScoreSerializer_v2168_hotfix4 extends SetScoreSerializer_v2168 {
    public static final SetScoreSerializer_v2168_hotfix4 INSTANCE = new SetScoreSerializer_v2168_hotfix4();

    private static final String[] TYPE_NAMES = {
            "remove", "changeplayer", "changeentity", "changefakeplayer"
    };

    private SetScoreSerializer_v2168_hotfix4() {
    }

    @Override
    public void serialize(ByteBuf buffer, BedrockCodecHelper helper, SetScorePacket packet) {
        helper.writeArray(buffer, packet.getInfos(), (buf, scoreInfo) -> {
            VarInts.writeUnsignedInt(buf, scoreInfo.getType().ordinal());
            helper.writeString(buf, TYPE_NAMES[scoreInfo.getType().ordinal()]);
            VarInts.writeLong(buf, scoreInfo.getScoreboardId());

            switch (scoreInfo.getType()) {
                case INVALID -> {
                    // The first boolean is the 1.26.44-only discriminator. The second, when true,
                    // is the ordinary optional-string presence flag.
                    buf.writeBoolean(true);
                    helper.writeOptional(buf, value -> !value.isEmpty(), scoreInfo.getObjectiveId(), helper::writeString);
                }
                case ENTITY, PLAYER -> {
                    helper.writeString(buf, nonEmpty(scoreInfo.getObjectiveId()));
                    buf.writeIntLE(scoreInfo.getScore());
                    VarInts.writeLong(buf, scoreInfo.getEntityId());
                }
                case FAKE -> {
                    helper.writeString(buf, nonEmpty(scoreInfo.getObjectiveId()));
                    buf.writeIntLE(scoreInfo.getScore());
                    helper.writeString(buf, nonEmpty(scoreInfo.getName()));
                }
            }
        });
    }

    @Override
    public void deserialize(ByteBuf buffer, BedrockCodecHelper helper, SetScorePacket packet) {
        helper.readArray(buffer, packet.getInfos(), buf -> {
            ScoreInfo.ScorerType type = ScoreInfo.ScorerType.values()[VarInts.readUnsignedInt(buf)];
            helper.readString(buf);
            long scoreboardId = VarInts.readLong(buf);

            return switch (type) {
                case INVALID -> {
                    String objectiveId = "";
                    if (buf.readBoolean()) {
                        String value = helper.readOptional(buf, null, helper::readString);
                        objectiveId = value == null ? "" : value;
                    }
                    yield new ScoreInfo(scoreboardId, objectiveId, 0);
                }
                case ENTITY, PLAYER -> new ScoreInfo(
                        scoreboardId,
                        helper.readString(buf),
                        buf.readIntLE(),
                        type,
                        VarInts.readLong(buf)
                );
                case FAKE -> new ScoreInfo(
                        scoreboardId,
                        helper.readString(buf),
                        buf.readIntLE(),
                        helper.readString(buf)
                );
            };
        });
    }

    private static String nonEmpty(String value) {
        return value == null || value.isEmpty() ? " " : value;
    }
}
