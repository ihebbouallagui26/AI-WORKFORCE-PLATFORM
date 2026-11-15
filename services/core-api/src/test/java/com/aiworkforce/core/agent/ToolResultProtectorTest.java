package com.aiworkforce.core.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/** Security fix 8.1: the tool result envelope. Pure unit tests (no Spring, no database). */
class ToolResultProtectorTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID EXECUTION = UUID.randomUUID();
    private static final UUID INVOCATION = UUID.randomUUID();
    private static final String RESULT = "ACCOUNT-BALANCE=1234.56 ; token=sk-test-NOT-A-REAL-KEY-0123456789 ; é漢字";

    private static byte[] key() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }

    private final ToolResultProtector protector = new ToolResultProtector(key());

    // ---- HKDF (RFC 5869 test vectors 1 and 3)

    @Test
    void hkdfMatchesTheRfc5869TestVectors() {
        HexFormat hex = HexFormat.of();
        byte[] okm1 = Hkdf.derive(hex.parseHex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"), hex.parseHex("000102030405060708090a0b0c"),
                hex.parseHex("f0f1f2f3f4f5f6f7f8f9"), 42);
        assertThat(hex.formatHex(okm1)).isEqualTo("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865");
        byte[] okm3 = Hkdf.derive(hex.parseHex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"), new byte[0], new byte[0], 42);
        assertThat(hex.formatHex(okm3)).isEqualTo("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8");
    }

    @Test
    void differentPurposesDeriveDifferentKeysAndNeitherIsTheMasterKey() {
        byte[] master = key();
        byte[] salt = "salt".getBytes(StandardCharsets.US_ASCII);
        byte[] a = Hkdf.derive(master, salt, "tool-result".getBytes(StandardCharsets.US_ASCII), 32);
        byte[] b = Hkdf.derive(master, salt, "something-else".getBytes(StandardCharsets.US_ASCII), 32);
        assertThat(a).isNotEqualTo(b).isNotEqualTo(master);
        assertThat(Hkdf.derive(master, salt, "tool-result".getBytes(StandardCharsets.US_ASCII), 32)).isEqualTo(a);   // deterministic
    }

    // ---- envelope

    @Test
    void roundTripsAnyText() {
        for (String text : new String[] {RESULT, "", "x".repeat(8000), "\u0000\n\t"}) {
            byte[] envelope = protector.seal(ORG, EXECUTION, INVOCATION, text);
            assertThat(protector.open(ORG, EXECUTION, INVOCATION, envelope)).isEqualTo(text);
        }
    }

    @Test
    void theEnvelopeIsVersionedUsesAFreshNonceAndHoldsNoPlaintext() {
        byte[] first = protector.seal(ORG, EXECUTION, INVOCATION, RESULT);
        byte[] second = protector.seal(ORG, EXECUTION, INVOCATION, RESULT);
        assertThat(first[0]).isEqualTo(ToolResultProtector.FORMAT_V1);
        assertThat(first[1]).isEqualTo(ToolResultProtector.KEY_ID_1);
        assertThat(Arrays.copyOfRange(first, 2, 14)).isNotEqualTo(Arrays.copyOfRange(second, 2, 14));      // 96-bit nonce per encryption
        assertThat(first).isNotEqualTo(second);
        String raw = new String(first, StandardCharsets.ISO_8859_1);
        assertThat(raw).doesNotContain("ACCOUNT-BALANCE").doesNotContain("sk-test").doesNotContain("1234.56");
        assertThat(first.length).isEqualTo(2 + 12 + RESULT.getBytes(StandardCharsets.UTF_8).length + 16);
    }

    @Test
    void aWrongKeyCannotOpenIt() {
        byte[] envelope = protector.seal(ORG, EXECUTION, INVOCATION, RESULT);
        assertThatThrownBy(() -> new ToolResultProtector(key()).open(ORG, EXECUTION, INVOCATION, envelope))
                .isInstanceOf(ToolResultProtector.ToolResultUnavailableException.class);
    }

    @Test
    void aCiphertextCannotBeMovedToAnotherOrganizationExecutionOrInvocation() {
        byte[] envelope = protector.seal(ORG, EXECUTION, INVOCATION, RESULT);
        assertThatThrownBy(() -> protector.open(UUID.randomUUID(), EXECUTION, INVOCATION, envelope)).isInstanceOf(ToolResultProtector.ToolResultUnavailableException.class);
        assertThatThrownBy(() -> protector.open(ORG, UUID.randomUUID(), INVOCATION, envelope)).isInstanceOf(ToolResultProtector.ToolResultUnavailableException.class);
        assertThatThrownBy(() -> protector.open(ORG, EXECUTION, UUID.randomUUID(), envelope)).isInstanceOf(ToolResultProtector.ToolResultUnavailableException.class);
        assertThat(protector.open(ORG, EXECUTION, INVOCATION, envelope)).isEqualTo(RESULT);             // and the original binding still works
    }

    @Test
    void everySingleByteOfTheEnvelopeIsAuthenticated() {
        byte[] envelope = protector.seal(ORG, EXECUTION, INVOCATION, "short result");
        for (int i = 0; i < envelope.length; i++) {
            byte[] tampered = envelope.clone();
            tampered[i] ^= 0x01;
            int position = i;
            assertThatThrownBy(() -> protector.open(ORG, EXECUTION, INVOCATION, tampered))
                    .as("flipping byte %d must be detected", position).isInstanceOf(ToolResultProtector.ToolResultUnavailableException.class);
        }
    }

    @Test
    void truncatedEmptyOrNullEnvelopesAreRefused() {
        byte[] envelope = protector.seal(ORG, EXECUTION, INVOCATION, RESULT);
        for (byte[] bad : new byte[][] {null, new byte[0], new byte[10], Arrays.copyOf(envelope, envelope.length - 1), Arrays.copyOf(envelope, 29)}) {
            assertThatThrownBy(() -> protector.open(ORG, EXECUTION, INVOCATION, bad)).isInstanceOf(ToolResultProtector.ToolResultUnavailableException.class);
        }
    }

    @Test
    void anUnknownFormatOrKeyIdIsRefusedAndThereIsNoDowngradeToAnOlderForm() {
        byte[] envelope = protector.seal(ORG, EXECUTION, INVOCATION, RESULT);
        for (byte version : new byte[] {0, 2, (byte) 255}) {
            byte[] changed = envelope.clone();
            changed[0] = version;
            assertThatThrownBy(() -> protector.open(ORG, EXECUTION, INVOCATION, changed)).isInstanceOf(ToolResultProtector.ToolResultUnavailableException.class);
        }
        byte[] otherKeyId = envelope.clone();
        otherKeyId[1] = 9;
        assertThatThrownBy(() -> protector.open(ORG, EXECUTION, INVOCATION, otherKeyId)).isInstanceOf(ToolResultProtector.ToolResultUnavailableException.class);
        // raw plaintext bytes (what a downgrade attacker would store) are not an envelope either
        assertThatThrownBy(() -> protector.open(ORG, EXECUTION, INVOCATION, RESULT.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ToolResultProtector.ToolResultUnavailableException.class);
    }

    @Test
    void theMasterKeyItselfCannotOpenAnEnvelopeSoThePurposeKeyIsReallyDerived() throws Exception {
        byte[] master = key();
        ToolResultProtector derived = new ToolResultProtector(master);
        // A ciphertext produced with the MASTER key directly, in the same envelope layout and with the same AAD structure.
        byte[] nonce = new byte[12];
        new SecureRandom().nextBytes(nonce);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(master, "AES"), new GCMParameterSpec(128, nonce));
        ByteBuffer aad = ByteBuffer.allocate(15 + 2 + 48).put("aiw.tool-result".getBytes(StandardCharsets.US_ASCII)).put((byte) 1).put((byte) 1);
        for (UUID id : new UUID[] {ORG, EXECUTION, INVOCATION}) {
            aad.putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits());
        }
        cipher.updateAAD(aad.array());
        byte[] ciphertext = cipher.doFinal(RESULT.getBytes(StandardCharsets.UTF_8));
        byte[] envelope = ByteBuffer.allocate(2 + 12 + ciphertext.length).put((byte) 1).put((byte) 1).put(nonce).put(ciphertext).array();
        assertThatThrownBy(() -> derived.open(ORG, EXECUTION, INVOCATION, envelope)).isInstanceOf(ToolResultProtector.ToolResultUnavailableException.class);
    }

    @Test
    void failuresNeverContainThePlaintextTheKeyOrAnIdentifier() {
        byte[] envelope = protector.seal(ORG, EXECUTION, INVOCATION, RESULT);
        envelope[20] ^= 0x7f;
        assertThatThrownBy(() -> protector.open(ORG, EXECUTION, INVOCATION, envelope)).satisfies(e -> {
            assertThat(e.getMessage()).isEqualTo("The tool result is unavailable.");
            assertThat(e.getCause()).isNull();
            assertThat(e.toString()).doesNotContain("ACCOUNT").doesNotContain(ORG.toString()).doesNotContain(INVOCATION.toString());
        });
    }

    @Test
    void itSurvivesAnApplicationRestartBecauseTheKeyIsTheOnlyState() {
        byte[] master = key();
        byte[] envelope = new ToolResultProtector(master).seal(ORG, EXECUTION, INVOCATION, RESULT);
        assertThat(new ToolResultProtector(master).open(ORG, EXECUTION, INVOCATION, envelope)).isEqualTo(RESULT);   // a brand-new instance
    }

    @Test
    void theMasterKeyMustBeExactly32Bytes() {
        assertThatThrownBy(() -> new ToolResultProtector(new byte[16])).isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("0x");
        assertThatThrownBy(() -> new ToolResultProtector((byte[]) null)).isInstanceOf(IllegalArgumentException.class);
    }
}
