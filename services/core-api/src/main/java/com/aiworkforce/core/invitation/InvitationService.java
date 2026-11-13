package com.aiworkforce.core.invitation;

import com.aiworkforce.core.access.MembershipService;
import com.aiworkforce.core.access.OrganizationAccess;
import com.aiworkforce.core.access.Permission;
import com.aiworkforce.core.access.RoleService;
import com.aiworkforce.core.access.RoleView;
import com.aiworkforce.core.access.TenantAccessService;
import com.aiworkforce.core.audit.AuditLog;
import com.aiworkforce.core.common.exception.BadRequestException;
import com.aiworkforce.core.common.exception.ConflictException;
import com.aiworkforce.core.common.exception.ResourceNotFoundException;
import com.aiworkforce.core.identity.AuthenticatedUser;
import com.aiworkforce.core.identity.UserDirectory;
import com.aiworkforce.core.organization.OrganizationService;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Invitations: creation (with anti-escalation check of the offered role), listing, revocation
 * and acceptance (single use, not expired, verified e-mail matching the invitation).
 */
@Service
public class InvitationService {

    /** Result of an acceptance: the organization joined. */
    public record Acceptance(UUID organizationId, String organizationName, UUID membershipId) {
    }

    private final InvitationRepository invitations;
    private final InvitationNotifier notifier;
    private final InvitationProperties properties;
    private final TenantAccessService access;
    private final RoleService roles;
    private final MembershipService memberships;
    private final OrganizationService organizations;
    private final UserDirectory users;
    private final AuditLog audit;
    private final Clock clock;

    public InvitationService(InvitationRepository invitations, InvitationNotifier notifier,
            InvitationProperties properties, TenantAccessService access, RoleService roles,
            MembershipService memberships, OrganizationService organizations, UserDirectory users, AuditLog audit,
            Clock clock) {
        this.invitations = invitations;
        this.notifier = notifier;
        this.properties = properties;
        this.access = access;
        this.roles = roles;
        this.memberships = memberships;
        this.organizations = organizations;
        this.users = users;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public InvitationView create(UUID organizationId, AuthenticatedUser user, String email, UUID roleId) {
        OrganizationAccess actor = access.require(organizationId, user, Permission.INVITATION_CREATE);
        RoleView role = roles.requireGrantableRole(actor, roleId);
        String normalizedEmail = normalize(email);
        if (memberships.anyCurrentMember(organizationId, users.findIdsByVerifiedEmail(normalizedEmail))) {
            throw new ConflictException("This person is already a member of the organization.");
        }
        Instant now = Instant.now(clock);
        invitations.findByOrganizationIdAndEmailAndStatus(organizationId, normalizedEmail,
                OrganizationInvitation.Status.PENDING).ifPresent(existing -> {
                    if (!existing.isExpired(now)) {
                        throw new ConflictException("A pending invitation already exists for this e-mail address.");
                    }
                    // An expired invitation no longer blocks a new one.
                    existing.revoke(now);
                    invitations.flush();
                });

        String token = InvitationTokens.newToken();
        OrganizationInvitation invitation = invitations.save(new OrganizationInvitation(organizationId,
                normalizedEmail, role.id(), InvitationTokens.hash(token), user.userId(), now,
                now.plus(properties.ttl())));
        invitations.flush();
        audit.record(organizationId, user.userId(), "invitation.created", "invitation", invitation.getId(),
                Map.of("email", normalizedEmail, "roleId", role.id().toString()));
        // Last step: if delivery fails, the whole transaction (invitation + audit) rolls back.
        notifier.sendInvitation(normalizedEmail, organizations.nameOf(organizationId), role.name(),
                user.displayName(), token, invitation.getExpiresAt());
        return InvitationView.of(invitation);
    }

    @Transactional(readOnly = true)
    public Page<InvitationView> listPending(UUID organizationId, AuthenticatedUser user, Pageable pageable) {
        access.require(organizationId, user, Permission.INVITATION_READ);
        return invitations.findByOrganizationIdAndStatus(organizationId, OrganizationInvitation.Status.PENDING,
                pageable).map(InvitationView::of);
    }

    @Transactional
    public void revoke(UUID organizationId, UUID invitationId, AuthenticatedUser user) {
        access.require(organizationId, user, Permission.INVITATION_REVOKE);
        OrganizationInvitation invitation = invitations.findByOrganizationIdAndId(organizationId, invitationId)
                .orElseThrow(() -> new ResourceNotFoundException("invitation"));
        if (invitation.getStatus() != OrganizationInvitation.Status.PENDING) {
            throw new ConflictException("Only pending invitations can be revoked.");
        }
        invitation.revoke(Instant.now(clock));
        invitations.flush();
        audit.record(organizationId, user.userId(), "invitation.revoked", "invitation", invitationId,
                Map.of("email", invitation.getEmail()));
    }

    /**
     * Accepts an invitation. Authorization is never based on the e-mail alone: the caller is
     * identified by its {@code (issuer, subject)}, must hold the token <em>and</em> must have a
     * verified e-mail address equal to the invited one.
     */
    @Transactional
    public Acceptance accept(AuthenticatedUser user, String token) {
        if (!InvitationTokens.isWellFormed(token)) {
            throw new BadRequestException("Malformed invitation code.");
        }
        OrganizationInvitation invitation = invitations
                .findOrganizationInvitationByTokenHashForUpdate(InvitationTokens.hash(token))
                .orElseThrow(() -> new ResourceNotFoundException("invitation"));
        Instant now = Instant.now(clock);
        if (invitation.getStatus() != OrganizationInvitation.Status.PENDING) {
            throw new ConflictException("This invitation is no longer valid.");
        }
        if (invitation.isExpired(now)) {
            throw new ConflictException("This invitation has expired.");
        }
        if (!user.emailVerified() || user.email() == null
                || !normalize(user.email()).equals(invitation.getEmail())) {
            throw new AccessDeniedException("This invitation was sent to another e-mail address.");
        }
        UUID organizationId = invitation.getOrganizationId();
        // The grant must still be legitimate now: the inviter is an active member allowed to invite
        // and to grant this role.
        OrganizationAccess inviter = access.findActiveAccess(organizationId, invitation.getInvitedBy())
                .filter(a -> a.has(Permission.INVITATION_CREATE))
                .orElseThrow(() -> new ConflictException("This invitation is no longer valid."));
        try {
            roles.requireGrantableRole(inviter, invitation.getRoleId());
        } catch (AccessDeniedException | ResourceNotFoundException e) {
            throw new ConflictException("This invitation is no longer valid.");
        }

        UUID membershipId = memberships.admit(organizationId, user.userId(), invitation.getRoleId(),
                invitation.getInvitedBy());
        invitation.accept(user.userId(), now);
        invitations.flush();
        audit.record(organizationId, user.userId(), "invitation.accepted", "invitation", invitation.getId(),
                Map.of("membershipId", membershipId.toString()));
        return new Acceptance(organizationId, organizations.nameOf(organizationId), membershipId);
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
