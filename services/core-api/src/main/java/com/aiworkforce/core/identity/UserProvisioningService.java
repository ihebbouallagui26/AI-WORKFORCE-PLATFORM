package com.aiworkforce.core.identity;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Maps a validated access token to an internal {@link UserAccount}.
 *
 * <ul>
 *   <li>The key is {@code (iss, sub)}. The e-mail is never used to find or link accounts.</li>
 *   <li>First login creates the account and the external identity (just-in-time provisioning).</li>
 *   <li>{@code email} and {@code email_verified} are re-synchronised from the identity provider,
 *       because invitation acceptance depends on them.</li>
 *   <li>Two first requests racing on the unique {@code (issuer, subject)} constraint are resolved
 *       by retrying the lookup.</li>
 * </ul>
 */
@Service
public class UserProvisioningService {

    private static final Logger log = LoggerFactory.getLogger(UserProvisioningService.class);

    private final UserAccountRepository users;
    private final ExternalIdentityRepository identities;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public UserProvisioningService(
            UserAccountRepository users,
            ExternalIdentityRepository identities,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.users = users;
        this.identities = identities;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public AuthenticatedUser resolve(Jwt jwt) {
        AuthenticatedUser user;
        try {
            user = transaction.execute(status -> resolveOrCreate(jwt));
        } catch (DataIntegrityViolationException concurrentFirstLogin) {
            log.debug("Concurrent first login detected, re-reading the external identity");
            user = transaction.execute(status -> resolveOrCreate(jwt));
        }
        if (user == null) {
            throw new IllegalStateException("User resolution returned no result");
        }
        return user;
    }

    private AuthenticatedUser resolveOrCreate(Jwt jwt) {
        String issuer = jwt.getIssuer() == null ? null : jwt.getIssuer().toString();
        String subject = jwt.getSubject();
        if (issuer == null || subject == null) {
            throw new AccessDeniedException("Token without issuer or subject");
        }
        Instant now = Instant.now(clock);
        String email = jwt.getClaimAsString("email");
        boolean emailVerified = Boolean.TRUE.equals(jwt.getClaimAsBoolean("email_verified"));

        Optional<ExternalIdentity> existing = identities.findByIssuerAndSubject(issuer, subject);
        UserAccount account;
        if (existing.isPresent()) {
            ExternalIdentity identity = existing.get();
            account = users.findById(identity.getUserId())
                    .orElseThrow(() -> new IllegalStateException("External identity without user account"));
            account.syncFromIdentityProvider(email, emailVerified, now);
            identity.touch(now);
        } else {
            account = users.save(new UserAccount(email, emailVerified, displayName(jwt, email), now));
            identities.save(new ExternalIdentity(account.getId(), issuer, subject, now));
            identities.flush();
            log.info("Provisioned user account {} for a new external identity", account.getId());
        }

        if (account.getStatus() != UserAccount.Status.ACTIVE) {
            throw new AccessDeniedException("User account is disabled");
        }
        return new AuthenticatedUser(account.getId(), account.getEmail(), account.isEmailVerified(),
                account.getDisplayName());
    }

    private static String displayName(Jwt jwt, String email) {
        for (String claim : new String[] {"name", "preferred_username"}) {
            String value = jwt.getClaimAsString(claim);
            if (value != null && !value.isBlank()) {
                return truncate(value.trim());
            }
        }
        return email != null && !email.isBlank() ? truncate(email.trim()) : "User";
    }

    private static String truncate(String value) {
        return value.length() > 200 ? value.substring(0, 200) : value;
    }
}
