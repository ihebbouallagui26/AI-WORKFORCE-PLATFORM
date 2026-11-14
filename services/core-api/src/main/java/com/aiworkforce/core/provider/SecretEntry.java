package com.aiworkforce.core.provider;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Encrypted secret row: ciphertext and nonce only. Never exposed outside the secret store. */
@Entity
@Table(name = "secret_entries")
class SecretEntry {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(nullable = false, updatable = false)
    private byte[] ciphertext;

    @Column(nullable = false, updatable = false)
    private byte[] nonce;

    @Column(name = "key_version", nullable = false, updatable = false)
    private int keyVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected SecretEntry() {
    }

    SecretEntry(UUID id, UUID organizationId, byte[] ciphertext, byte[] nonce, int keyVersion, Instant createdAt) {
        this.id = id;
        this.organizationId = organizationId;
        this.ciphertext = ciphertext;
        this.nonce = nonce;
        this.keyVersion = keyVersion;
        this.createdAt = createdAt;
    }

    UUID getId() { return id; }
    byte[] getCiphertext() { return ciphertext; }
    byte[] getNonce() { return nonce; }
}
