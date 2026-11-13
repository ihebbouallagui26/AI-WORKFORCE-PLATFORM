package com.aiworkforce.core.organization;

import com.aiworkforce.core.access.OrganizationAccess;
import com.aiworkforce.core.access.MembershipService;
import com.aiworkforce.core.access.OrganizationTenancy;
import com.aiworkforce.core.access.Permission;
import com.aiworkforce.core.access.RoleService;
import com.aiworkforce.core.access.RoleView;
import com.aiworkforce.core.access.TenantAccessService;
import com.aiworkforce.core.audit.AuditEntry;
import com.aiworkforce.core.audit.AuditLog;
import com.aiworkforce.core.common.exception.BadRequestException;
import com.aiworkforce.core.common.exception.ConflictException;
import com.aiworkforce.core.common.exception.ResourceNotFoundException;
import com.aiworkforce.core.identity.AuthenticatedUser;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Organization lifecycle. Every operation on an existing organization starts with the tenant check. */
@Service
public class OrganizationService {

    /** Upper bound on the organizations one user may belong to (abuse protection). */
    static final int MAX_MEMBERSHIPS_PER_USER = 100;

    /** Organization details as seen by one member (with the caller's own roles and permissions). */
    public record OrganizationView(OrganizationSummary organization, UUID myMembershipId, List<RoleView> myRoles,
            List<Permission> myPermissions) {
    }

    private final OrganizationRepository organizations;
    private final OrganizationTenancy tenancy;
    private final TenantAccessService access;
    private final MembershipService memberships;
    private final RoleService roles;
    private final AuditLog audit;
    private final Clock clock;

    public OrganizationService(OrganizationRepository organizations, OrganizationTenancy tenancy,
            TenantAccessService access, MembershipService memberships, RoleService roles, AuditLog audit,
            Clock clock) {
        this.organizations = organizations;
        this.tenancy = tenancy;
        this.access = access;
        this.memberships = memberships;
        this.roles = roles;
        this.audit = audit;
        this.clock = clock;
    }

    /** Creates the organization, its built-in roles and the creator's OWNER membership atomically. */
    @Transactional
    public OrganizationView create(AuthenticatedUser user, String name, String description, String timezone) {
        if (memberships.activeOrganizationIdsOf(user.userId()).size() >= MAX_MEMBERSHIPS_PER_USER) {
            throw new ConflictException("You belong to too many organizations.");
        }
        Organization organization = organizations.save(new Organization(name.trim(), clean(description),
                zone(timezone), user.userId(), Instant.now(clock)));
        organizations.flush();
        tenancy.bootstrap(organization.getId(), user.userId());
        audit.record(organization.getId(), user.userId(), "organization.created", "organization",
                organization.getId(), Map.of("name", organization.getName()));
        return view(organization, access.requireMember(organization.getId(), user));
    }

    /** Organizations in which the caller is an ACTIVE member. */
    @Transactional(readOnly = true)
    public Page<OrganizationSummary> listMine(AuthenticatedUser user, Pageable pageable) {
        List<UUID> ids = memberships.activeOrganizationIdsOf(user.userId());
        return organizations.findByIdIn(ids, pageable).map(OrganizationSummary::of);
    }

    @Transactional(readOnly = true)
    public OrganizationView get(UUID organizationId, AuthenticatedUser user) {
        OrganizationAccess actor = access.require(organizationId, user, Permission.ORGANIZATION_READ);
        return view(load(organizationId), actor);
    }

    @Transactional
    public OrganizationView update(UUID organizationId, AuthenticatedUser user, String name, String description,
            String timezone) {
        OrganizationAccess actor = access.require(organizationId, user, Permission.ORGANIZATION_UPDATE);
        Organization organization = load(organizationId);
        Map<String, Object> changes = new LinkedHashMap<>();
        if (name != null && !name.trim().equals(organization.getName())) {
            changes.put("name", name.trim());
        }
        if (description != null) {
            changes.put("descriptionChanged", true);
        }
        if (timezone != null) {
            changes.put("timezone", zone(timezone));
        }
        organization.update(name == null ? organization.getName() : name.trim(),
                description == null ? organization.getDescription() : clean(description),
                timezone == null ? organization.getTimezone() : zone(timezone), Instant.now(clock));
        organizations.flush();
        audit.record(organizationId, user.userId(), "organization.updated", "organization", organizationId, changes);
        return view(organization, actor);
    }

    @Transactional(readOnly = true)
    public Page<AuditEntry> auditEvents(UUID organizationId, AuthenticatedUser user, Pageable pageable) {
        access.require(organizationId, user, Permission.AUDIT_READ);
        return audit.findForOrganization(organizationId, pageable);
    }

    /** Name of an organization, for modules that already performed the tenant check. */
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public String nameOf(UUID organizationId) {
        return load(organizationId).getName();
    }

    private Organization load(UUID organizationId) {
        return organizations.findById(organizationId).orElseThrow(() -> new ResourceNotFoundException("organization"));
    }

    private OrganizationView view(Organization organization, OrganizationAccess actor) {
        Collection<RoleView> myRoles = roles.viewsByIds(organization.getId(), actor.roleIds()).values();
        return new OrganizationView(OrganizationSummary.of(organization), actor.membershipId(),
                myRoles.stream().sorted(java.util.Comparator.comparing(RoleView::name)).toList(),
                actor.permissions().stream().sorted().toList());
    }

    private static String clean(String description) {
        return description == null ? "" : description.trim();
    }

    private static String zone(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            return "UTC";
        }
        try {
            return ZoneId.of(timezone.trim()).getId();
        } catch (DateTimeException e) {
            throw new BadRequestException("Unknown time zone.");
        }
    }
}
