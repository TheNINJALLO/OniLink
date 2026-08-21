package dev.onistone.onilink.control.wire;

import dev.onistone.onilink.control.ControlJson;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public final class ControlFrameCodec {
    private ControlFrameCodec() {
    }

    public static void write(DataOutputStream output, Map<String, Object> envelope, int maximumBytes) throws IOException {
        byte[] bytes = ControlJson.encode(envelope).getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > maximumBytes) throw new IOException("ONICTL frame size is invalid");
        output.writeInt(bytes.length);
        output.write(bytes);
        output.flush();
    }

    public static Map<String, Object> read(DataInputStream input, int maximumBytes) throws IOException {
        int size;
        try {
            size = input.readInt();
        } catch (EOFException exception) {
            throw new EOFException("ONICTL connection closed before a frame header");
        }
        if (size <= 0 || size > maximumBytes) throw new IOException("ONICTL frame size is invalid");
        byte[] bytes = input.readNBytes(size);
        if (bytes.length != size) throw new EOFException("ONICTL connection closed inside a frame");
        String json;
        try {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            json = decoded.toString();
        } catch (CharacterCodingException exception) {
            throw new IOException("ONICTL frame is not valid UTF-8", exception);
        }
        try {
            return ControlJson.parseObject(json, maximumBytes);
        } catch (IllegalArgumentException exception) {
            throw new IOException("ONICTL frame is not valid JSON: " + exception.getMessage(), exception);
        }
    }
}
