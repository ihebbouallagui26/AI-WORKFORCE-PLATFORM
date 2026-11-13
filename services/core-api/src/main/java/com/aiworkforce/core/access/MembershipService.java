package com.aiworkforce.core.access;

import com.aiworkforce.core.audit.AuditLog;
import com.aiworkforce.core.common.exception.ConflictException;
import com.aiworkforce.core.common.exception.ResourceNotFoundException;
import com.aiworkforce.core.identity.AuthenticatedUser;
import com.aiworkforce.core.identity.UserDirectory;
import com.aiworkforce.core.identity.UserDirectory.UserSummary;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Memberships of an organization: listing, suspension, re-activation, removal and role assignment.
 *
 * <p>Invariants enforced here:
 * <ul>
 *   <li>an organization always keeps at least one ACTIVE member holding the OWNER role (changes
 *       are serialised by a row lock on the organization);</li>
 *   <li>an actor can only act on a member whose roles it could grant itself (an ADMIN cannot
 *       suspend, remove or re-role an OWNER, nor grant the OWNER role);</li>
 *   <li>a member always has at least one role.</li>
 * </ul>
 */
@Service
public class MembershipService {

    private static final Set<Membership.Status> VISIBLE_STATUSES =
            EnumSet.of(Membership.Status.ACTIVE, Membership.Status.SUSPENDED);

    private final MembershipRepository memberships;
    private final MembershipRoleRepository membershipRoles;
    private final RoleService roles;
    private final TenantAccessService access;
    private final UserDirectory users;
    private final AuditLog audit;
    private final Clock clock;

    public MembershipService(MembershipRepository memberships, MembershipRoleRepository membershipRoles,
            RoleService roles, TenantAccessService access, UserDirectory users, AuditLog audit, Clock clock) {
        this.memberships = memberships;
        this.membershipRoles = membershipRoles;
        this.roles = roles;
        this.access = access;
        this.users = users;
        this.audit = audit;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ queries

    @Transactional(readOnly = true)
    public Page<MemberView> listMembers(UUID organizationId, AuthenticatedUser user, boolean includeSuspended,
            Pageable pageable) {
        access.require(organizationId, user, Permission.MEMBER_READ);
        Set<Membership.Status> statuses = includeSuspended ? VISIBLE_STATUSES : EnumSet.of(Membership.Status.ACTIVE);
        Page<Membership> page = memberships.findByOrganizationIdAndStatusIn(organizationId, statuses, pageable);
        Map<UUID, MemberView> views = views(organizationId, page.getContent()).stream()
                .collect(Collectors.toMap(MemberView::membershipId, v -> v));
        return page.map(m -> views.get(m.getId()));
    }

    @Transactional(readOnly = true)
    public MemberView getMember(UUID organizationId, UUID membershipId, AuthenticatedUser user) {
        access.require(organizationId, user, Permission.MEMBER_READ);
        return view(organizationId, loadVisible(organizationId, membershipId));
    }

    /** Organizations where the user is an ACTIVE member. */
    @Transactional(readOnly = true)
    public List<UUID> activeOrganizationIdsOf(UUID userId) {
        return memberships.findActiveOrganizationIdsOfUser(userId);
    }

    /** Whether one of the users already has an ACTIVE or SUSPENDED membership. */
    @Transactional(readOnly = true)
    public boolean anyCurrentMember(UUID organizationId, Collection<UUID> userIds) {
        return !userIds.isEmpty()
                && memberships.existsByOrganizationIdAndUserIdInAndStatusIn(organizationId, userIds, VISIBLE_STATUSES);
    }

    // ------------------------------------------------------------------ commands

    @Transactional
    public MemberView suspend(UUID organizationId, UUID membershipId, AuthenticatedUser user) {
        OrganizationAccess actor = access.require(organizationId, user, Permission.MEMBER_MANAGE);
        lock(organizationId);
        Membership target = loadVisible(organizationId, membershipId);
        requireNotSelf(actor, target, "suspend");
        requireCanActOn(actor, target);
        if (target.getStatus() != Membership.Status.ACTIVE) {
            throw new ConflictException("Only active members can be suspended.");
        }
        requireNotLastOwner(organizationId, target);
        target.changeStatus(Membership.Status.SUSPENDED, Instant.now(clock));
        memberships.flush();
        audit.record(organizationId, user.userId(), "member.suspended", "membership", membershipId,
                Map.of("userId", target.getUserId().toString()));
        return view(organizationId, target);
    }

    @Transactional
    public MemberView reactivate(UUID organizationId, UUID membershipId, AuthenticatedUser user) {
        OrganizationAccess actor = access.require(organizationId, user, Permission.MEMBER_MANAGE);
        lock(organizationId);
        Membership target = loadVisible(organizationId, membershipId);
        requireCanActOn(actor, target);
        if (target.getStatus() != Membership.Status.SUSPENDED) {
            throw new ConflictException("Only suspended members can be reactivated.");
        }
        target.changeStatus(Membership.Status.ACTIVE, Instant.now(clock));
        memberships.flush();
        audit.record(organizationId, user.userId(), "member.reactivated", "membership", membershipId,
                Map.of("userId", target.getUserId().toString()));
        return view(organizationId, target);
    }

    /**
     * Removes a member. Members may always leave an organization themselves (unless they are its
     * last owner); removing someone else requires {@code member.manage}.
     */
    @Transactional
    public void remove(UUID organizationId, UUID membershipId, AuthenticatedUser user) {
        OrganizationAccess actor = access.requireMember(organizationId, user);
        lock(organizationId);
        Membership target = loadVisible(organizationId, membershipId);
        boolean leaving = target.getId().equals(actor.membershipId());
        if (!leaving) {
            actor.require(Permission.MEMBER_MANAGE);
            requireCanActOn(actor, target);
        }
        requireNotLastOwner(organizationId, target);
        membershipRoles.deleteByOrganizationIdAndMembershipId(organizationId, membershipId);
        Membership reloaded = loadVisible(organizationId, membershipId);
        reloaded.changeStatus(Membership.Status.REMOVED, Instant.now(clock));
        memberships.flush();
        audit.record(organizationId, user.userId(), leaving ? "member.left" : "member.removed", "membership",
                membershipId, Map.of("userId", target.getUserId().toString()));
    }

    /** Replaces the roles of a member (at least one role). */
    @Transactional
    public MemberView setRoles(UUID organizationId, UUID membershipId, AuthenticatedUser user, Set<UUID> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            throw new ConflictException("A member must have at least one role.");
        }
        OrganizationAccess actor = access.require(organizationId, user, Permission.MEMBER_ROLES_ASSIGN);
        lock(organizationId);
        Membership target = loadVisible(organizationId, membershipId);
        List<RoleView> requested = roles.requireRoles(organizationId, roleIds);
        Set<UUID> current = currentRoleIds(organizationId, membershipId);

        Set<UUID> added = new HashSet<>(roleIds);
        added.removeAll(current);
        Set<UUID> removed = new HashSet<>(current);
        removed.removeAll(roleIds);
        if (added.isEmpty() && removed.isEmpty()) {
            return view(organizationId, target);
        }
        // Every role that changes hands must be grantable by the actor.
        Map<UUID, RoleView> involved = new java.util.HashMap<>(roles.viewsByIds(organizationId, removed));
        requested.forEach(r -> involved.put(r.id(), r));
        for (UUID roleId : union(added, removed)) {
            RoleGrantPolicy.requireCanGrantOrRevoke(actor, involved.get(roleId));
        }

        boolean losesOwner = removed.stream().map(involved::get).anyMatch(RoleView::isOwnerRole);
        if (losesOwner && target.getStatus() == Membership.Status.ACTIVE
                && membershipRoles.countActiveOwnersInOrganization(organizationId) <= 1) {
            throw new ConflictException("The organization must keep at least one active owner.");
        }

        Instant now = Instant.now(clock);
        membershipRoles.deleteByOrganizationIdAndMembershipId(organizationId, membershipId);
        Membership reloaded = loadVisible(organizationId, membershipId);
        for (UUID roleId : roleIds) {
            membershipRoles.save(new MembershipRole(reloaded, roles.requireRoleEntity(organizationId, roleId),
                    user.userId(), now));
        }
        membershipRoles.flush();
        audit.record(organizationId, user.userId(), "member.roles.changed", "membership", membershipId,
                Map.of("userId", target.getUserId().toString(),
                        "added", sorted(added), "removed", sorted(removed)));
        return view(organizationId, reloaded);
    }

    // ------------------------------------------------------------------ internal API (same transaction)

    /** Creates the founding membership of a new organization with the OWNER role. */
    @Transactional(propagation = Propagation.MANDATORY)
    void createOwnerMembership(UUID organizationId, UUID userId, Role ownerRole) {
        Instant now = Instant.now(clock);
        Membership membership = memberships.save(new Membership(organizationId, userId, now));
        membershipRoles.save(new MembershipRole(membership, ownerRole, userId, now));
    }

    /**
     * Admits a user into the organization with one role (invitation acceptance). The grant was
     * authorised when the invitation was created; a removed member is re-admitted.
     *
     * @throws ConflictException if the user is already an active or suspended member
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID admit(UUID organizationId, UUID userId, UUID roleId, UUID grantedBy) {
        lock(organizationId);
        Instant now = Instant.now(clock);
        Membership membership = memberships.findByOrganizationIdAndUserId(organizationId, userId).orElse(null);
        if (membership == null) {
            membership = memberships.save(new Membership(organizationId, userId, now));
        } else if (membership.getStatus() == Membership.Status.REMOVED) {
            membership.rejoin(now);
        } else {
            throw new ConflictException("You are already a member of this organization.");
        }
        membershipRoles.save(new MembershipRole(membership, roles.requireRoleEntity(organizationId, roleId),
                grantedBy, now));
        memberships.flush();
        return membership.getId();
    }

    // ------------------------------------------------------------------ helpers

    private void lock(UUID organizationId) {
        memberships.lockOrganization(organizationId).orElseThrow(() -> new ResourceNotFoundException("organization"));
    }

    private Membership loadVisible(UUID organizationId, UUID membershipId) {
        return memberships.findByOrganizationIdAndId(organizationId, membershipId)
                .filter(m -> m.getStatus() != Membership.Status.REMOVED)
                .orElseThrow(() -> new ResourceNotFoundException("member"));
    }

    private Set<UUID> currentRoleIds(UUID organizationId, UUID membershipId) {
        return membershipRoles.findByOrganizationIdAndIdMembershipId(organizationId, membershipId).stream()
                .map(MembershipRole::getRoleId).collect(Collectors.toSet());
    }

    /** The actor must be able to grant every role the target holds (protects owners from admins). */
    private void requireCanActOn(OrganizationAccess actor, Membership target) {
        Map<UUID, RoleView> targetRoles =
                roles.viewsByIds(actor.organizationId(), currentRoleIds(actor.organizationId(), target.getId()));
        targetRoles.values().forEach(role -> RoleGrantPolicy.requireCanGrantOrRevoke(actor, role));
    }

    private static void requireNotSelf(OrganizationAccess actor, Membership target, String action) {
        if (target.getId().equals(actor.membershipId())) {
            throw new ConflictException("You cannot " + action + " your own membership.");
        }
    }

    private void requireNotLastOwner(UUID organizationId, Membership target) {
        if (target.getStatus() != Membership.Status.ACTIVE) {
            return;
        }
        boolean isOwner = roles.viewsByIds(organizationId, currentRoleIds(organizationId, target.getId())).values()
                .stream().anyMatch(RoleView::isOwnerRole);
        if (isOwner && membershipRoles.countActiveOwnersInOrganization(organizationId) <= 1) {
            throw new ConflictException("The organization must keep at least one active owner.");
        }
    }

    private MemberView view(UUID organizationId, Membership membership) {
        return views(organizationId, List.of(membership)).getFirst();
    }

    private List<MemberView> views(UUID organizationId, List<Membership> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = list.stream().map(Membership::getId).toList();
        Map<UUID, List<UUID>> roleIdsByMembership = membershipRoles
                .findByOrganizationIdAndIdMembershipIdIn(organizationId, ids).stream()
                .collect(Collectors.groupingBy(MembershipRole::getMembershipId,
                        Collectors.mapping(MembershipRole::getRoleId, Collectors.toList())));
        Map<UUID, RoleView> roleViews = roles.viewsByIds(organizationId,
                roleIdsByMembership.values().stream().flatMap(List::stream).collect(Collectors.toSet()));
        Map<UUID, UserSummary> profiles = users.findByIds(list.stream().map(Membership::getUserId).toList());
        return list.stream().map(m -> {
            UserSummary profile = profiles.get(m.getUserId());
            List<MemberView.RoleRef> refs = roleIdsByMembership.getOrDefault(m.getId(), List.of()).stream()
                    .map(roleViews::get)
                    .filter(java.util.Objects::nonNull)
                    .sorted(Comparator.comparing(RoleView::builtIn).reversed().thenComparing(RoleView::name))
                    .map(r -> new MemberView.RoleRef(r.id(), r.code(), r.name()))
                    .toList();
            return new MemberView(m.getId(), m.getOrganizationId(), m.getUserId(),
                    profile == null ? null : profile.email(), profile == null ? null : profile.displayName(),
                    m.getStatus(), m.getJoinedAt(), m.getStatusChangedAt(), refs);
        }).toList();
    }

    private static Set<UUID> union(Set<UUID> a, Set<UUID> b) {
        Set<UUID> all = new LinkedHashSet<>(a);
        all.addAll(b);
        return all;
    }

    private static List<String> sorted(Set<UUID> ids) {
        return ids.stream().map(UUID::toString).sorted().toList();
    }
}
