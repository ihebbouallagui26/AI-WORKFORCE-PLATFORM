package com.aiworkforce.core.invitation;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Invitation settings ({@code app.invitations.*}). */
@Validated
@ConfigurationProperties("app.invitations")
public record InvitationProperties(@NotNull Duration ttl, @NotBlank @Email String mailFrom) {

    public InvitationProperties {
        if (ttl != null && (ttl.isNegative() || ttl.isZero() || ttl.compareTo(Duration.ofDays(30)) > 0)) {
            throw new IllegalArgumentException("app.invitations.ttl must be between 1 second and 30 days");
        }
    }
}
