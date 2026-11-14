package com.aiworkforce.core.identity;

import com.aiworkforce.core.common.id.Ids;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** Link between an OpenID Connect identity {@code (issuer, subject)} and a {@link UserAccount}. */
@Entity
@Table(name = "external_identities")
public class ExternalIdentity {

    /** Avoids a database write on every request just to refresh the last-login timestamp. */
    private static final Duration LAST_LOGIN_RESOLUTION = Duration.ofMinutes(5);

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private String issuer;

    @Column(nullable = false)
    private String subject;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_login_at", nullable = false)
    private Instant lastLoginAt;

    protected ExternalIdentity() {
    }

    ExternalIdentity(UUID userId, String issuer, String subject, Instant now) {
        this.id = Ids.newId();
        this.userId = userId;
        this.issuer = issuer;
        this.subject = subject;
        this.createdAt = now;
        this.lastLoginAt = now;
    }

    boolean touch(Instant now) {
        if (lastLoginAt.plus(LAST_LOGIN_RESOLUTION).isAfter(now)) {
            return false;
        }
        lastLoginAt = now;
        return true;
    }

    public UUID getUserId() {
        return userId;
    }
}
