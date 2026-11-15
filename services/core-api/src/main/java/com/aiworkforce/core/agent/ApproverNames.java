package com.aiworkforce.core.agent;

import com.aiworkforce.core.access.MembershipService;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * The human identity shown for a person who decided an approval (security/UX fix 8.5): the member's display name, resolved only among the
 * members of THIS organization, or the neutral {@link #FALLBACK} when it is unavailable (no profile, blank name, not a member of this
 * organization). Nothing else about the person (no e-mail, no id) leaves this class, and nothing is ever invented.
 */
@Component
class ApproverNames {

    static final String FALLBACK = "Organization member";

    private final MembershipService memberships;

    ApproverNames(MembershipService memberships) {
        this.memberships = memberships;
    }

    /** One entry per distinct non-null user id: a display name or the fallback. Callers must have authorized the request already. */
    Map<UUID, String> resolve(UUID organizationId, Collection<UUID> userIds) {
        var ids = userIds.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return new java.util.HashMap<>();   // (a HashMap: get(null) for an undecided approval must not throw)
        }
        Map<UUID, String> known = memberships.displayNamesOfUsers(organizationId, ids);
        return ids.stream().collect(Collectors.toMap(id -> id, id -> known.getOrDefault(id, FALLBACK), (a, b) -> a, java.util.HashMap::new));
    }
}
