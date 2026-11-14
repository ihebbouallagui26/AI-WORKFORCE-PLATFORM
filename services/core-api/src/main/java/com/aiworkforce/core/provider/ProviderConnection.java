package com.aiworkforce.core.provider;

import com.aiworkforce.core.common.id.Ids;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * An organization's account with an AI provider. It holds a reference to the encrypted credential
 * (never the credential) and the outcome of the last validation.
 */
@Entity
@Table(name = "provider_connections")
public class ProviderConnection {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider_type", nullable = false, updatable = false)
    private ProviderType providerType;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "account_identifier")
    private String accountIdentifier;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ConnectionStatus status;

    @Column(name = "status_detail")
    private String statusDetail;

    @Column(name = "credential_ref")
    private UUID credentialRef;

    @Column(name = "last_tested_at")
    private Instant lastTestedAt;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected ProviderConnection() {
    }

    ProviderConnection(UUID organizationId, ProviderType type, String displayName, String accountIdentifier,
            UUID credentialRef, UUID createdBy, Instant now) {
        this.id = Ids.newId();
        this.organizationId = organizationId;
        this.providerType = type;
        this.displayName = displayName;
        this.accountIdentifier = accountIdentifier;
        this.credentialRef = credentialRef;
        this.status = ConnectionStatus.PENDING;
        this.createdBy = createdBy;
        this.createdAt = now;
        this.updatedAt = now;
    }

    void update(String displayName, String accountIdentifier, Instant now) {
        this.displayName = displayName;
        this.accountIdentifier = accountIdentifier;
        this.updatedAt = now;
    }

    /** A new credential: the connection is not valid until it has been tested again. */
    void replaceCredential(UUID credentialRef, Instant now) {
        this.credentialRef = credentialRef;
        this.status = ConnectionStatus.PENDING;
        this.statusDetail = null;
        this.lastTestedAt = null;
        this.updatedAt = now;
    }

    void recordTest(ConnectionStatus status, String detail, Instant now) {
        this.status = status;
        this.statusDetail = detail;
        this.lastTestedAt = now;
        this.updatedAt = now;
    }

    void noteUnavailable(String detail, Instant now) {
        this.statusDetail = detail;
        this.updatedAt = now;
    }

    void revoke(Instant now) {
        this.status = ConnectionStatus.REVOKED;
        this.credentialRef = null;
        this.statusDetail = null;
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public ProviderType getProviderType() { return providerType; }
    public String getDisplayName() { return displayName; }
    public String getAccountIdentifier() { return accountIdentifier; }
    public ConnectionStatus getStatus() { return status; }
    public String getStatusDetail() { return statusDetail; }
    UUID getCredentialRef() { return credentialRef; }
    public Instant getLastTestedAt() { return lastTestedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
}
