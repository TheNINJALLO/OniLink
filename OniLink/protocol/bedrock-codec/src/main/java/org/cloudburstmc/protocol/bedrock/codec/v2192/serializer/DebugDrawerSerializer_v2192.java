package org.cloudburstmc.protocol.bedrock.codec.v2192.serializer;

import io.netty.buffer.ByteBuf;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.v1001.serializer.DebugDrawerSerializer_v1001;
import org.cloudburstmc.protocol.bedrock.data.debugshape.*;
import org.cloudburstmc.protocol.common.util.VarInts;

import java.awt.Color;

/** Protocol 2192's line-gap field in text debug shapes. */
public final class DebugDrawerSerializer_v2192 extends DebugDrawerSerializer_v1001 {
    public static final DebugDrawerSerializer_v2192 INSTANCE = new DebugDrawerSerializer_v2192();

    private DebugDrawerSerializer_v2192() {
    }

    @Override
    protected void writeShape(ByteBuf buffer, BedrockCodecHelper helper, DebugShape shape) {
        if (shape.getType() != DebugShape.Type.TEXT) {
            super.writeShape(buffer, helper, shape);
            return;
        }
        writeCommonShapeData(buffer, helper, shape);
        VarInts.writeUnsignedInt(buffer, toPayloadType(DebugShape.Type.TEXT));
        DebugText text = (DebugText) shape;
        helper.writeString(buffer, text.getText());
        buffer.writeBoolean(text.isUseRotation());
        helper.writeOptionalNull(buffer, text.getBackgroundColor(),
                (buf, h, color) -> buf.writeIntLE(color.getRGB()));
        buffer.writeFloatLE(text.getLineGapHeight());
        buffer.writeBoolean(text.isDepthTest());
        buffer.writeBoolean(text.isShowBackface());
        buffer.writeBoolean(text.isShowTextBackface());
    }

    @Override
    protected DebugShape readShape(ByteBuf buffer, BedrockCodecHelper helper) {
        long id = VarInts.readUnsignedLong(buffer);
        DebugShape.Type type = helper.readOptional(buffer, null,
                (buf, h) -> SHAPE_TYPES[buf.readUnsignedByte()]);
        Vector3f position = helper.readOptional(buffer, null, READ_VECTOR3F);
        Float scale = helper.readOptional(buffer, null, ByteBuf::readFloatLE);
        Vector3f rotation = helper.readOptional(buffer, null, READ_VECTOR3F);
        Float totalTimeLeft = helper.readOptional(buffer, null, ByteBuf::readFloatLE);
        Float maximumRenderDistance = helper.readOptional(buffer, null, ByteBuf::readFloatLE);
        Color color = helper.readOptional(buffer, null, READ_COLOR);
        Integer dimension = helper.readOptional(buffer, -1, VarInts::readInt);
        Long attachedToEntityId = helper.readOptional(buffer, null, VarInts::readUnsignedLong);
        VarInts.readUnsignedInt(buffer);

        if (type == null) {
            return new DebugShape(id, dimension);
        }

        DebugShape shape = switch (type) {
            case ARROW -> new DebugArrow();
            case BOX -> new DebugBox();
            case CIRCLE -> new DebugCircle();
            case LINE -> new DebugLine();
            case SPHERE -> new DebugSphere();
            case TEXT -> new DebugText();
            case CYLINDER -> new DebugCylinder();
            case PYRAMID -> new DebugPyramid();
            case ELLIPSOID -> new DebugEllipsoid();
            case CONE -> new DebugCone();
        };
        shape.setId(id);
        shape.setDimension(dimension);
        shape.setPosition(position);
        shape.setScale(scale);
        shape.setRotation(rotation);
        shape.setTotalTimeLeft(totalTimeLeft);
        shape.setColor(color);
        shape.setAttachedToEntityId(attachedToEntityId);
        shape.setMaximumRenderDistance(maximumRenderDistance);

        switch (type) {
            case ARROW -> {
                DebugArrow arrow = (DebugArrow) shape;
                arrow.setArrowEndPosition(helper.readOptional(buffer, null, READ_VECTOR3F));
                arrow.setArrowHeadLength(helper.readOptional(buffer, null, ByteBuf::readFloatLE));
                arrow.setArrowHeadRadius(helper.readOptional(buffer, null, ByteBuf::readFloatLE));
                arrow.setArrowHeadSegments(helper.readOptional(buffer, null, buf -> (int) buf.readUnsignedByte()));
            }
            case BOX -> ((DebugBox) shape).setBoxBounds(helper.readVector3f(buffer));
            case CIRCLE -> ((DebugCircle) shape).setSegments((int) buffer.readUnsignedByte());
            case LINE -> ((DebugLine) shape).setLineEndPosition(helper.readVector3f(buffer));
            case SPHERE -> ((DebugSphere) shape).setSegments((int) buffer.readUnsignedByte());
            case TEXT -> {
                DebugText text = (DebugText) shape;
                text.setText(helper.readString(buffer));
                text.setUseRotation(buffer.readBoolean());
                text.setBackgroundColor(helper.readOptional(buffer, null,
                        (buf, h) -> new Color(buf.readIntLE(), true)));
                text.setLineGapHeight(buffer.readFloatLE());
                text.setDepthTest(buffer.readBoolean());
                text.setShowBackface(buffer.readBoolean());
                text.setShowTextBackface(buffer.readBoolean());
            }
            case CYLINDER -> {
                DebugCylinder cylinder = (DebugCylinder) shape;
                cylinder.setRadiusX(helper.readVector2f(buffer));
                cylinder.setRadiusZ(helper.readVector2f(buffer));
                cylinder.setHeight(buffer.readFloatLE());
                cylinder.setSegments(buffer.readUnsignedByte());
            }
            case PYRAMID -> {
                DebugPyramid pyramid = (DebugPyramid) shape;
                pyramid.setWidth(buffer.readFloatLE());
                pyramid.setDepth(helper.readOptional(buffer, null, ByteBuf::readFloatLE));
                pyramid.setHeight(buffer.readFloatLE());
            }
            case ELLIPSOID -> {
                DebugEllipsoid ellipsoid = (DebugEllipsoid) shape;
                ellipsoid.setRadii(helper.readVector3f(buffer));
                ellipsoid.setSegments(buffer.readUnsignedByte());
            }
            case CONE -> {
                DebugCone cone = (DebugCone) shape;
                cone.setRadii(helper.readVector2f(buffer));
                cone.setHeight(buffer.readFloatLE());
                cone.setSegments(buffer.readUnsignedByte());
            }
        }
        return shape;
    }
}
