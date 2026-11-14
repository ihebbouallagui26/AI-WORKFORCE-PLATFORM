package com.aiworkforce.core.provider;

import java.util.Optional;
import java.util.UUID;

/**
 * Storage of secrets (provider credentials). Implementations must keep secrets encrypted at rest
 * and never expose them except through {@link #reveal}, which is reserved to server-side adapters
 * that call the provider. A secret is addressed by an opaque reference that carries no information.
 *
 * <p>The development implementation is {@code AesGcmSecretStore}. A production deployment can
 * supply another implementation (cloud KMS, Vault) without touching callers.
 */
public interface SecretStore {

    /** Encrypts and stores the secret for the organization; returns its reference. */
    UUID store(UUID organizationId, String secret);

    /** Decrypts a secret of the organization. Empty if the reference does not exist in that organization. */
    Optional<String> reveal(UUID organizationId, UUID reference);

    /** Deletes the secret (idempotent). */
    void delete(UUID organizationId, UUID reference);
}
