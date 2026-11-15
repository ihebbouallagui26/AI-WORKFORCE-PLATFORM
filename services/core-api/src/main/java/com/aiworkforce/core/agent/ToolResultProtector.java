package com.aiworkforce.core.agent;

import com.aiworkforce.core.provider.SecretsProperties;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Authenticated encryption of a tool result at rest (security fix 8.1).
 *
 * <h2>Design</h2>
 * <ul>
 *   <li><b>Algorithm</b>: AES-256-GCM, a fresh random 96-bit nonce per encryption, 128-bit tag.</li>
 *   <li><b>Key</b>: derived with HKDF-SHA256 from the platform master key ({@code app.secrets.master-key}) with a fixed salt and the
 *       purpose label {@code tool-result} plus the key id. The master key is never used directly, and a key derived for another purpose
 *       is different: the same stored bytes cannot be opened with the provider-credential or the approval-arguments keys.</li>
 *   <li><b>Envelope</b> (versioned, self-describing): {@code [format=1][keyId=1][nonce:12][ciphertext+tag]}. An unknown format or key id is
 *       refused (no silent fallback, no attacker-chosen downgrade); there is no plaintext form of an envelope.</li>
 *   <li><b>AAD</b> (authenticated, never stored): a purpose label, the format, the key id and the IMMUTABLE ids of the organization, the
 *       execution and the tool invocation. A ciphertext copied to another organization, execution or invocation, or re-labelled for another
 *       purpose, fails authentication.</li>
 * </ul>
 * Failures never contain the plaintext, the key or a cryptographic detail.
 */
@Component
class ToolResultProtector {

    static final byte FORMAT_V1 = 1;
    static final byte KEY_ID_1 = 1;
    static final int NONCE_BYTES = 12;
    static final int TAG_BITS = 128;
    static final int MIN_ENVELOPE_BYTES = 2 + NONCE_BYTES + TAG_BITS / 8;
    private static final byte[] PURPOSE = "aiw.tool-result".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] SALT = "aiworkforce.core.key-derivation.v1".getBytes(StandardCharsets.US_ASCII);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    @Autowired
    ToolResultProtector(SecretsProperties properties) {
        this(Base64.getDecoder().decode(properties.masterKey().trim()));
    }

    /** Test seam and the real constructor: the 32-byte master key. */
    ToolResultProtector(byte[] masterKey) {
        if (masterKey == null || masterKey.length != 32) {
            throw new IllegalArgumentException("The master key must be exactly 32 bytes");
        }
        this.key = new SecretKeySpec(Hkdf.derive(masterKey, SALT, info(KEY_ID_1), 32), "AES");
    }

    private static byte[] info(byte keyId) {
        return ("tool-result|key-id=" + keyId).getBytes(StandardCharsets.US_ASCII);
    }

    byte[] seal(UUID organizationId, UUID executionId, UUID invocationId, String plaintext) {
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(FORMAT_V1, KEY_ID_1, organizationId, executionId, invocationId));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(2 + NONCE_BYTES + ciphertext.length).put(FORMAT_V1).put(KEY_ID_1).put(nonce).put(ciphertext).array();
        } catch (GeneralSecurityException e) {
            throw new ToolResultUnavailableException();
        }
    }

    /** Authenticates and decrypts; fails closed (generic exception) for any wrong key, id, tampering, truncation or unknown version. */
    String open(UUID organizationId, UUID executionId, UUID invocationId, byte[] envelope) {
        if (envelope == null || envelope.length < MIN_ENVELOPE_BYTES || envelope[0] != FORMAT_V1 || envelope[1] != KEY_ID_1) {
            throw new ToolResultUnavailableException();
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, envelope, 2, NONCE_BYTES));
            cipher.updateAAD(aad(envelope[0], envelope[1], organizationId, executionId, invocationId));
            return new String(cipher.doFinal(envelope, 2 + NONCE_BYTES, envelope.length - 2 - NONCE_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new ToolResultUnavailableException();
        }
    }

    private static byte[] aad(byte format, byte keyId, UUID organizationId, UUID executionId, UUID invocationId) {
        ByteBuffer buffer = ByteBuffer.allocate(PURPOSE.length + 2 + 48).put(PURPOSE).put(format).put(keyId);
        for (UUID id : new UUID[] {organizationId, executionId, invocationId}) {
            buffer.putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits());
        }
        return buffer.array();
    }

    /** Generic, detail-free failure. */
    static final class ToolResultUnavailableException extends IllegalStateException {

        ToolResultUnavailableException() {
            super("The tool result is unavailable.");
        }
    }
}
