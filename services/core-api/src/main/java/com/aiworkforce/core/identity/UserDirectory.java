package com.aiworkforce.core.identity;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read and profile operations on users, exposed to other modules (they never touch the repositories). */
@Service
public class UserDirectory {

    /** Public view of a user for other modules (e.g. member lists). */
    public record UserSummary(UUID id, String email, boolean emailVerified, String displayName) {
    }

    private final UserAccountRepository users;
    private final Clock clock;

    public UserDirectory(UserAccountRepository users, Clock clock) {
        this.users = users;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Map<UUID, UserSummary> findByIds(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return users.findByIdIn(ids).stream()
                .map(UserDirectory::summary)
                .collect(Collectors.toMap(UserSummary::id, Function.identity()));
    }

    /** Users whose e-mail address is verified by the identity provider. */
    @Transactional(readOnly = true)
    public List<UUID> findIdsByVerifiedEmail(String email) {
        return users.findByEmailAndEmailVerifiedTrue(UserAccount.normalizeEmail(email)).stream()
                .map(UserAccount::getId)
                .toList();
    }

    @Transactional
    public UserSummary rename(UUID userId, String displayName) {
        UserAccount account = users.findById(userId)
                .orElseThrow(() -> new IllegalStateException("Authenticated user no longer exists"));
        account.rename(displayName.trim(), Instant.now(clock));
        return summary(account);
    }

    private static UserSummary summary(UserAccount account) {
        return new UserSummary(account.getId(), account.getEmail(), account.isEmailVerified(),
                account.getDisplayName());
    }
}
