package com.aiworkforce.core.identity;

import com.aiworkforce.core.common.id.Ids;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/** Application user. Identity comes from {@link ExternalIdentity}, never from the e-mail. */
@Entity
@Table(name = "user_accounts")
public class UserAccount {

    public enum Status { ACTIVE, DISABLED }

    @Id
    private UUID id;

    @Column(nullable = false)
    private String email;

    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected UserAccount() {
    }

    UserAccount(String email, boolean emailVerified, String displayName, Instant now) {
        this.id = Ids.newId();
        this.email = normalizeEmail(email);
        this.emailVerified = emailVerified;
        this.displayName = displayName;
        this.status = Status.ACTIVE;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Synchronises security-relevant claims from the identity provider. Returns true if changed. */
    boolean syncFromIdentityProvider(String email, boolean emailVerified, Instant now) {
        String normalized = normalizeEmail(email);
        if (normalized.equals(this.email) && emailVerified == this.emailVerified) {
            return false;
        }
        this.email = normalized;
        this.emailVerified = emailVerified;
        this.updatedAt = now;
        return true;
    }

    void rename(String displayName, Instant now) {
        this.displayName = displayName;
        this.updatedAt = now;
    }

    public static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public boolean isEmailVerified() {
        return emailVerified;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
