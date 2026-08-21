package dev.onistone.onilink.control.wire;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;

public final class ControlSigner {
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private ControlSigner() {
    }

    public static String signRequest(ControlEnvelope envelope, byte[] secret) {
        return hmac(envelope.signatureInput(), secret);
    }

    public static String signResponse(ControlResponseEnvelope envelope, byte[] secret) {
        return hmac(envelope.signatureInput(), secret);
    }

    public static boolean verifyRequest(ControlEnvelope envelope, byte[] secret) {
        return verify(envelope.signature(), signRequest(envelope, secret));
    }

    public static boolean verifyResponse(ControlResponseEnvelope envelope, byte[] secret) {
        return verify(envelope.signature(), signResponse(envelope, secret));
    }

    public static boolean verify(String supplied, String expected) {
        try {
            byte[] left = DECODER.decode(supplied == null ? "" : supplied);
            byte[] right = DECODER.decode(expected == null ? "" : expected);
            boolean matches = MessageDigest.isEqual(left, right);
            java.util.Arrays.fill(left, (byte) 0);
            java.util.Arrays.fill(right, (byte) 0);
            return matches;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static String hmac(String input, byte[] secret) {
        if (secret == null || secret.length < 32) throw new IllegalArgumentException("control secret is too short");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return ENCODER.encodeToString(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HmacSHA256 is unavailable", exception);
        }
    }
}
