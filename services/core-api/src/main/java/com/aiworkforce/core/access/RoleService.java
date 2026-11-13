package com.aiworkforce.core.access;

import com.aiworkforce.core.audit.AuditLog;
import com.aiworkforce.core.common.exception.ConflictException;
import com.aiworkforce.core.common.exception.ResourceNotFoundException;
import com.aiworkforce.core.identity.AuthenticatedUser;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Organization roles: built-in roles (immutable) and custom roles with explicit permissions. */
@Service
public class RoleService {

    static final int MAX_CUSTOM_ROLES = 50;

    private final RoleRepository roles;
    private final RolePermissionRepository rolePermissions;
    private final MembershipRoleRepository membershipRoles;
    private final PermissionDefinitionRepository permissionCatalog;
    private final TenantAccessService access;
    private final AuditLog audit;
    private final Clock clock;

    public RoleService(RoleRepository roles, RolePermissionRepository rolePermissions,
            MembershipRoleRepository membershipRoles, PermissionDefinitionRepository permissionCatalog,
            TenantAccessService access, AuditLog audit, Clock clock) {
        this.roles = roles;
        this.rolePermissions = rolePermissions;
        this.membershipRoles = membershipRoles;
        this.permissionCatalog = permissionCatalog;
        this.access = access;
        this.audit = audit;
        this.clock = clock;
    }

    /** Entry of the global permission catalog. */
    public record PermissionInfo(String code, String description) {
    }

    /** Global catalog of permissions (codes and descriptions). */
    @Transactional(readOnly = true)
    public List<PermissionInfo> permissionCatalog() {
        return permissionCatalog.findAll(Sort.by("code")).stream()
                .map(p -> new PermissionInfo(p.getCode(), p.getDescription()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<RoleView> listRoles(UUID organizationId, AuthenticatedUser user) {
        access.require(organizationId, user, Permission.ROLE_READ);
        return views(organizationId, roles.findByOrganizationIdOrderByBuiltInDescNameAsc(organizationId));
    }

    @Transactional(readOnly = true)
    public RoleView getRole(UUID organizationId, UUID roleId, AuthenticatedUser user) {
        access.require(organizationId, user, Permission.ROLE_READ);
        return view(organizationId, load(organizationId, roleId));
    }

    @Transactional
    public RoleView createRole(UUID organizationId, AuthenticatedUser user, String name, String description,
            Set<Permission> permissions) {
        OrganizationAccess actor = access.require(organizationId, user, Permission.ROLE_MANAGE);
        RoleGrantPolicy.requireCanDefineCustomRole(actor, permissions);
        String cleanName = name.trim();
        if (roles.existsByOrganizationIdAndNameIgnoreCase(organizationId, cleanName)) {
            throw new ConflictException("A role with this name already exists.");
        }
        if (roles.countByOrganizationIdAndBuiltInFalse(organizationId) >= MAX_CUSTOM_ROLES) {
            throw new ConflictException("An organization can define at most " + MAX_CUSTOM_ROLES + " custom roles.");
        }
        Role role = roles.save(Role.custom(organizationId, cleanName, normalize(description), Instant.now(clock)));
        replacePermissions(role, permissions);
        audit.record(organizationId, user.userId(), "role.created", "role", role.getId(),
                Map.of("name", role.getName(), "permissions", codes(permissions)));
        return view(organizationId, role);
    }

    @Transactional
    public RoleView updateRole(UUID organizationId, UUID roleId, AuthenticatedUser user, String name,
            String description, Set<Permission> permissions) {
        OrganizationAccess actor = access.require(organizationId, user, Permission.ROLE_MANAGE);
        Role role = load(organizationId, roleId);
        requireCustom(role);
        // Modifying a role is equivalent to granting/revoking it: the actor must hold what it grants.
        RoleGrantPolicy.requireCanGrantOrRevoke(actor, view(organizationId, role));
        Instant now = Instant.now(clock);
        if (name != null || description != null) {
            String nextName = name == null ? role.getName() : name.trim();
            if (roles.existsByOrganizationIdAndNameIgnoreCaseAndIdNot(organizationId, nextName, roleId)) {
                throw new ConflictException("A role with this name already exists.");
            }
            role.rename(nextName, description == null ? role.getDescription() : normalize(description), now);
        }
        if (permissions != null) {
            RoleGrantPolicy.requireCanDefineCustomRole(actor, permissions);
            rolePermissions.deleteByOrganizationIdAndRoleId(organizationId, roleId);
            role = load(organizationId, roleId);
            replacePermissions(role, permissions);
            role.touch(now);
        }
        audit.record(organizationId, user.userId(), "role.updated", "role", roleId,
                Map.of("name", role.getName(), "permissionsChanged", permissions != null));
        return view(organizationId, role);
    }

    @Transactional
    public void deleteRole(UUID organizationId, UUID roleId, AuthenticatedUser user) {
        OrganizationAccess actor = access.require(organizationId, user, Permission.ROLE_MANAGE);
        Role role = load(organizationId, roleId);
        requireCustom(role);
        RoleGrantPolicy.requireCanGrantOrRevoke(actor, view(organizationId, role));
        if (membershipRoles.existsByOrganizationIdAndIdRoleId(organizationId, roleId)) {
            throw new ConflictException("This role is assigned to members. Unassign it before deleting it.");
        }
        rolePermissions.deleteByOrganizationIdAndRoleId(organizationId, roleId);
        roles.delete(load(organizationId, roleId));
        roles.flush();
        audit.record(organizationId, user.userId(), "role.deleted", "role", roleId, Map.of("name", role.getName()));
    }

    // ------------------------------------------------------------------ internal API (same transaction)

    /** Creates the built-in roles of a new organization. Returns them by code. */
    @Transactional(propagation = Propagation.MANDATORY)
    Map<BuiltInRole, Role> createBuiltInRoles(UUID organizationId) {
        Instant now = Instant.now(clock);
        Map<BuiltInRole, Role> created = new java.util.EnumMap<>(BuiltInRole.class);
        for (BuiltInRole code : BuiltInRole.values()) {
            Role role = roles.save(Role.builtIn(organizationId, code, now));
            replacePermissions(role, code.permissions());
            created.put(code, role);
        }
        return created;
    }

    /** Loads roles of the actor's organization (404 if one of them belongs elsewhere or does not exist). */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<RoleView> requireRoles(UUID organizationId, Collection<UUID> roleIds) {
        List<Role> found = roles.findByOrganizationIdAndIdIn(organizationId, roleIds);
        if (found.size() != Set.copyOf(roleIds).size()) {
            throw new ResourceNotFoundException("role");
        }
        return views(organizationId, found);
    }

    /** The role must be grantable by the actor (anti-escalation). Used by invitations. */
    @Transactional(propagation = Propagation.MANDATORY)
    public RoleView requireGrantableRole(OrganizationAccess actor, UUID roleId) {
        RoleView role = view(actor.organizationId(), load(actor.organizationId(), roleId));
        RoleGrantPolicy.requireCanGrantOrRevoke(actor, role);
        return role;
    }

    @Transactional(readOnly = true)
    public Map<UUID, RoleView> viewsByIds(UUID organizationId, Collection<UUID> roleIds) {
        if (roleIds.isEmpty()) {
            return Map.of();
        }
        return views(organizationId, roles.findByOrganizationIdAndIdIn(organizationId, roleIds)).stream()
                .collect(Collectors.toMap(RoleView::id, view -> view));
    }

    Role requireRoleEntity(UUID organizationId, UUID roleId) {
        return load(organizationId, roleId);
    }

    // ------------------------------------------------------------------ helpers

    private Role load(UUID organizationId, UUID roleId) {
        return roles.findByOrganizationIdAndId(organizationId, roleId)
                .orElseThrow(() -> new ResourceNotFoundException("role"));
    }

    private void replacePermissions(Role role, Set<Permission> permissions) {
        rolePermissions.saveAll(permissions.stream().map(p -> new RolePermission(role, p)).toList());
    }

    private static void requireCustom(Role role) {
        if (role.isBuiltIn()) {
            throw new ConflictException("Built-in roles cannot be modified or deleted.");
        }
    }

    private RoleView view(UUID organizationId, Role role) {
        return views(organizationId, List.of(role)).getFirst();
    }

    private List<RoleView> views(UUID organizationId, List<Role> list) {
        Map<UUID, Set<Permission>> permissionsByRole = rolePermissions
                .findByOrganizationIdAndIdRoleIdIn(organizationId, list.stream().map(Role::getId).toList()).stream()
                .collect(Collectors.groupingBy(RolePermission::getRoleId,
                        Collectors.mapping(RolePermission::getPermission,
                                Collectors.toCollection(() -> EnumSet.noneOf(Permission.class)))));
        return list.stream()
                .sorted(Comparator.comparing(Role::isBuiltIn).reversed().thenComparing(Role::getName))
                .map(role -> new RoleView(role.getId(), role.getOrganizationId(), role.getCode(), role.getName(),
                        role.getDescription(), role.isBuiltIn(),
                        Set.copyOf(permissionsByRole.getOrDefault(role.getId(), EnumSet.noneOf(Permission.class)))))
                .toList();
    }

    private static String normalize(String description) {
        return description == null ? "" : description.trim();
    }

    private static List<String> codes(Set<Permission> permissions) {
        return permissions.stream().map(Permission::code).sorted().toList();
    }
}
