package com.aiworkforce.core.agent;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HKDF-SHA256 (RFC 5869). Used to derive a PURPOSE-SPECIFIC data key from the platform master key, so the master key itself is never
 * used directly for tool results and two purposes can never share a key. Pure function, no state.
 */
final class Hkdf {

    private static final String HMAC = "HmacSHA256";
    private static final int HASH_BYTES = 32;

    private Hkdf() {
    }

    static byte[] derive(byte[] inputKeyMaterial, byte[] salt, byte[] info, int length) {
        if (length < 1 || length > 255 * HASH_BYTES) {
            throw new IllegalArgumentException("invalid output length");
        }
        try {
            byte[] extractSalt = salt == null || salt.length == 0 ? new byte[HASH_BYTES] : salt;
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(extractSalt, HMAC));
            byte[] prk = mac.doFinal(inputKeyMaterial);
            mac.init(new SecretKeySpec(prk, HMAC));
            ByteBuffer out = ByteBuffer.allocate(((length + HASH_BYTES - 1) / HASH_BYTES) * HASH_BYTES);
            byte[] previous = new byte[0];
            for (int counter = 1; out.position() < length; counter++) {
                mac.update(previous);
                mac.update(info);
                mac.update((byte) counter);
                previous = mac.doFinal();
                out.put(previous);
            }
            Arrays.fill(prk, (byte) 0);
            return Arrays.copyOf(out.array(), length);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("The key derivation is unavailable");
        }
    }
}
