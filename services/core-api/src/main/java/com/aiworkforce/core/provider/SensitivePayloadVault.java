package com.aiworkforce.core.provider;

import com.aiworkforce.core.common.exception.ConflictException;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Encrypted storage for sensitive payloads that other modules must keep durably (for example the
 * pending arguments of a tool call waiting for human approval). It reuses the B3 {@link SecretStore}
 * (AES-256-GCM, random nonce, organization and entry bound as additional authenticated data): there is
 * no second encryption mechanism and no plaintext column.
 *
 * <p>It lives in the provider package because only that package may call {@link SecretStore#reveal}.
 * The organization is part of every operation: an entry of another organization cannot be opened.
 */
@Component
public class SensitivePayloadVault {

    private final SecretStore secrets;

    SensitivePayloadVault(SecretStore secrets) {
        this.secrets = secrets;
    }

    /** Encrypts and stores the payload; returns an opaque reference (no plaintext is kept anywhere else). */
    @Transactional
    public UUID seal(UUID organizationId, String payload) {
        return secrets.store(organizationId, payload);
    }

    /** Decrypts a payload sealed for this organization; fails closed if it is missing or cannot be authenticated. */
    @Transactional(readOnly = true)
    public String open(UUID organizationId, UUID reference) {
        return secrets.reveal(organizationId, reference)
                .orElseThrow(() -> new ConflictException("The stored payload is no longer available."));
    }

    /** Removes the encrypted payload (data minimization once it is no longer needed). */
    @Transactional
    public void discard(UUID organizationId, UUID reference) {
        secrets.delete(organizationId, reference);
    }
}
