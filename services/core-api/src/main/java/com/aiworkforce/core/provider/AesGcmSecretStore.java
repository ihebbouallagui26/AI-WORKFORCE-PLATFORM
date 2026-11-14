package com.aiworkforce.core.provider;

import com.aiworkforce.core.common.id.Ids;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Development secret store: AES-256-GCM with a fresh random 96-bit nonce per secret. The
 * organization id and the entry id are bound as additional authenticated data, so a ciphertext
 * copied to another organization or entry fails authentication. The master key comes only from
 * {@link SecretsProperties} (environment variable) and never reaches the database.
 *
 * <p>Failures never include the secret or any detail of the cryptographic error.
 */
@Service
class AesGcmSecretStore implements SecretStore {

    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_VERSION = 1;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretEntryRepository entries;
    private final SecretKeySpec key;
    private final Clock clock;

    AesGcmSecretStore(SecretEntryRepository entries, SecretsProperties properties, Clock clock) {
        this.entries = entries;
        this.key = new SecretKeySpec(Base64.getDecoder().decode(properties.masterKey().trim()), "AES");
        this.clock = clock;
    }

    @Override
    @Transactional
    public UUID store(UUID organizationId, String secret) {
        UUID id = Ids.newId();
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(organizationId, id));
            byte[] ciphertext = cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));
            entries.save(new SecretEntry(id, organizationId, ciphertext, nonce, KEY_VERSION, Instant.now(clock)));
            entries.flush();
            return id;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("The secret could not be encrypted");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> reveal(UUID organizationId, UUID reference) {
        return entries.findByOrganizationIdAndId(organizationId, reference).map(entry -> {
            try {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, entry.getNonce()));
                cipher.updateAAD(aad(organizationId, entry.getId()));
                return new String(cipher.doFinal(entry.getCiphertext()), StandardCharsets.UTF_8);
            } catch (GeneralSecurityException e) {
                // Wrong key, tampered or relocated ciphertext: no detail, never the secret.
                throw new IllegalStateException("The secret could not be decrypted");
            }
        });
    }

    @Override
    @Transactional
    public void delete(UUID organizationId, UUID reference) {
        entries.deleteByOrganizationIdAndId(organizationId, reference);
    }

    private static byte[] aad(UUID organizationId, UUID id) {
        return ByteBuffer.allocate(32)
                .putLong(organizationId.getMostSignificantBits()).putLong(organizationId.getLeastSignificantBits())
                .putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits())
                .array();
    }
}
