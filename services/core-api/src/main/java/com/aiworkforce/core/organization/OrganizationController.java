package com.aiworkforce.core.organization;

import com.aiworkforce.core.access.Permission;
import com.aiworkforce.core.audit.AuditEntry;
import com.aiworkforce.core.common.web.PageParams;
import com.aiworkforce.core.common.web.PageResponse;
import com.aiworkforce.core.identity.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Organizations of the authenticated user. */
@RestController
@Validated
@RequestMapping(path = "/api/v1/organizations", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Organizations", description = "Organizations (tenants)")
public class OrganizationController {

    @Schema(name = "OrganizationSummary")
    public record OrganizationSummaryResponse(UUID id, String name, String description, String timezone,
            Instant createdAt) {

        static OrganizationSummaryResponse of(OrganizationSummary o) {
            return new OrganizationSummaryResponse(o.id(), o.name(), o.description(), o.timezone(), o.createdAt());
        }
    }

    @Schema(name = "MyRole")
    public record MyRoleResponse(UUID id, String code, String name) {
    }

    @Schema(name = "Organization")
    public record OrganizationResponse(UUID id, String name, String description, String timezone,
            Instant createdAt, Instant updatedAt, UUID myMembershipId, List<MyRoleResponse> myRoles,
            List<String> myPermissions) {

        static OrganizationResponse of(OrganizationService.OrganizationView view) {
            OrganizationSummary o = view.organization();
            return new OrganizationResponse(o.id(), o.name(), o.description(), o.timezone(),
                    o.createdAt(), o.updatedAt(), view.myMembershipId(),
                    view.myRoles().stream().map(r -> new MyRoleResponse(r.id(),
                            r.code() == null ? null : r.code().name(), r.name())).toList(),
                    view.myPermissions().stream().map(Permission::code).sorted().toList());
        }
    }

    @Schema(name = "CreateOrganizationRequest")
    public record CreateOrganizationRequest(
            @NotBlank @Size(min = 2, max = 100) String name,
            @Size(max = 500) String description,
            @Size(max = 64) String timezone) {
    }

    @Schema(name = "UpdateOrganizationRequest", description = "Omitted fields are left unchanged.")
    public record UpdateOrganizationRequest(
            @Size(min = 2, max = 100) @Pattern(regexp = ".*\\S.*") String name,
            @Size(max = 500) String description,
            @Size(max = 64) String timezone) {
    }

    @Schema(name = "AuditEvent")
    public record AuditEventResponse(UUID id, UUID actorUserId, String action, String targetType, UUID targetId,
            String details, String requestId, Instant occurredAt) {

        static AuditEventResponse of(AuditEntry e) {
            return new AuditEventResponse(e.id(), e.actorUserId(), e.action(), e.targetType(),
                    e.targetId(), e.details(), e.requestId(), e.occurredAt());
        }
    }

    private final OrganizationService organizations;

    public OrganizationController(OrganizationService organizations) {
        this.organizations = organizations;
    }

    @GetMapping
    @Operation(summary = "List the organizations the caller is an active member of")
    public PageResponse<OrganizationSummaryResponse> list(@Parameter(hidden = true) AuthenticatedUser user,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(PageParams.MAX_SIZE) int size) {
        return PageResponse.of(organizations.listMine(user, PageParams.of(page, size, Sort.by("name", "id"))),
                OrganizationSummaryResponse::of);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create an organization; the caller becomes its OWNER")
    public OrganizationResponse create(@Parameter(hidden = true) AuthenticatedUser user,
            @Valid @RequestBody CreateOrganizationRequest request) {
        return OrganizationResponse.of(
                organizations.create(user, request.name(), request.description(), request.timezone()));
    }

    @GetMapping("/{organizationId}")
    @Operation(summary = "Get an organization with the caller's roles and permissions (organization.read)")
    public OrganizationResponse get(@PathVariable UUID organizationId, @Parameter(hidden = true) AuthenticatedUser user) {
        return OrganizationResponse.of(organizations.get(organizationId, user));
    }

    @PatchMapping(path = "/{organizationId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Update an organization (organization.update)")
    public OrganizationResponse update(@PathVariable UUID organizationId,
            @Parameter(hidden = true) AuthenticatedUser user, @Valid @RequestBody UpdateOrganizationRequest request) {
        return OrganizationResponse.of(organizations.update(organizationId, user, request.name(),
                request.description(), request.timezone()));
    }

    @GetMapping("/{organizationId}/audit-events")
    @Operation(summary = "List the organization's audit events, newest first (audit.read)")
    public PageResponse<AuditEventResponse> auditEvents(@PathVariable UUID organizationId,
            @Parameter(hidden = true) AuthenticatedUser user,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(PageParams.MAX_SIZE) int size) {
        return PageResponse.of(organizations.auditEvents(organizationId, user, PageParams.of(page, size, Sort.unsorted())),
                AuditEventResponse::of);
    }
}
