package com.aiworkforce.core.access;

import com.aiworkforce.core.common.exception.BadRequestException;
import com.aiworkforce.core.identity.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Roles of an organization and the global permission catalog. */
@RestController
@Tag(name = "Roles", description = "Organization roles and permissions")
public class RoleController {

    static final String PERMISSION_CODE = "^[a-z]+(\\.[a-z]+){1,3}$";

    @Schema(name = "Role")
    public record RoleResponse(UUID id, String code, String name, String description, boolean builtIn,
            List<String> permissions) {

        static RoleResponse of(RoleView view) {
            return new RoleResponse(view.id(), view.code() == null ? null : view.code().name(), view.name(),
                    view.description(), view.builtIn(), view.permissions().stream().map(Permission::code).sorted().toList());
        }
    }

    @Schema(name = "PermissionDefinition")
    public record PermissionResponse(String code, String description) {
    }

    @Schema(name = "CreateRoleRequest")
    public record CreateRoleRequest(
            @NotBlank @Size(min = 2, max = 60) String name,
            @Size(max = 300) String description,
            @NotNull @Size(max = 50) Set<@NotBlank @Pattern(regexp = PERMISSION_CODE) String> permissions) {
    }

    @Schema(name = "UpdateRoleRequest", description = "Omitted fields are left unchanged.")
    public record UpdateRoleRequest(
            @Size(min = 2, max = 60) @Pattern(regexp = ".*\\S.*") String name,
            @Size(max = 300) String description,
            @Size(max = 50) Set<@NotBlank @Pattern(regexp = PERMISSION_CODE) String> permissions) {
    }

    private final RoleService roles;

    public RoleController(RoleService roles) {
        this.roles = roles;
    }

    @GetMapping(path = "/api/v1/permissions", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "List the permission catalog")
    public List<PermissionResponse> permissions() {
        return roles.permissionCatalog().stream()
                .map(p -> new PermissionResponse(p.code(), p.description()))
                .toList();
    }

    @GetMapping(path = "/api/v1/organizations/{organizationId}/roles", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "List the roles of an organization (role.read)")
    public List<RoleResponse> list(@PathVariable UUID organizationId, @Parameter(hidden = true) AuthenticatedUser user) {
        return roles.listRoles(organizationId, user).stream().map(RoleResponse::of).toList();
    }

    @GetMapping(path = "/api/v1/organizations/{organizationId}/roles/{roleId}",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Get a role (role.read)")
    public RoleResponse get(@PathVariable UUID organizationId, @PathVariable UUID roleId,
            @Parameter(hidden = true) AuthenticatedUser user) {
        return RoleResponse.of(roles.getRole(organizationId, roleId, user));
    }

    @PostMapping(path = "/api/v1/organizations/{organizationId}/roles",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a custom role (role.manage; only permissions the caller holds)")
    public RoleResponse create(@PathVariable UUID organizationId, @Parameter(hidden = true) AuthenticatedUser user,
            @Valid @RequestBody CreateRoleRequest request) {
        return RoleResponse.of(roles.createRole(organizationId, user, request.name(), request.description(),
                toPermissions(request.permissions())));
    }

    @PatchMapping(path = "/api/v1/organizations/{organizationId}/roles/{roleId}",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Update a custom role (role.manage; built-in roles are immutable)")
    public RoleResponse update(@PathVariable UUID organizationId, @PathVariable UUID roleId,
            @Parameter(hidden = true) AuthenticatedUser user, @Valid @RequestBody UpdateRoleRequest request) {
        return RoleResponse.of(roles.updateRole(organizationId, roleId, user, request.name(), request.description(),
                request.permissions() == null ? null : toPermissions(request.permissions())));
    }

    @DeleteMapping("/api/v1/organizations/{organizationId}/roles/{roleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete an unassigned custom role (role.manage)")
    public void delete(@PathVariable UUID organizationId, @PathVariable UUID roleId,
            @Parameter(hidden = true) AuthenticatedUser user) {
        roles.deleteRole(organizationId, roleId, user);
    }

    static Set<Permission> toPermissions(Collection<String> codes) {
        Set<Permission> permissions = EnumSet.noneOf(Permission.class);
        for (String code : codes) {
            if (!Permission.isKnown(code)) {
                throw new BadRequestException("Unknown permission: " + code);
            }
            permissions.add(Permission.fromCode(code));
        }
        return permissions;
    }
}
