package com.aiworkforce.core.access;

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
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Members of an organization. */
@RestController
@Validated
@RequestMapping(path = "/api/v1/organizations/{organizationId}/members", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Members", description = "Organization memberships")
public class MemberController {

    @Schema(name = "RoleReference")
    public record RoleRefResponse(UUID id, String code, String name) {
    }

    @Schema(name = "Member")
    public record MemberResponse(UUID membershipId, UUID userId, String email, String displayName, String status,
            Instant joinedAt, Instant statusChangedAt, List<RoleRefResponse> roles) {

        static MemberResponse of(MemberView view) {
            return new MemberResponse(view.membershipId(), view.userId(), view.email(), view.displayName(),
                    view.status().name(), view.joinedAt(), view.statusChangedAt(),
                    view.roles().stream().map(r -> new RoleRefResponse(r.id(),
                            r.code() == null ? null : r.code().name(), r.name())).toList());
        }
    }

    @Schema(name = "SetMemberRolesRequest")
    public record SetRolesRequest(@NotNull @NotEmpty @Size(max = 20) Set<@NotNull UUID> roleIds) {
    }

    private final MembershipService memberships;

    public MemberController(MembershipService memberships) {
        this.memberships = memberships;
    }

    @GetMapping
    @Operation(summary = "List members (member.read)")
    public PageResponse<MemberResponse> list(@PathVariable UUID organizationId,
            @Parameter(hidden = true) AuthenticatedUser user,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(PageParams.MAX_SIZE) int size,
            @RequestParam(defaultValue = "false") boolean includeSuspended) {
        return PageResponse.of(memberships.listMembers(organizationId, user, includeSuspended,
                PageParams.of(page, size, Sort.by("joinedAt", "id"))), MemberResponse::of);
    }

    @GetMapping("/{membershipId}")
    @Operation(summary = "Get a member (member.read)")
    public MemberResponse get(@PathVariable UUID organizationId, @PathVariable UUID membershipId,
            @Parameter(hidden = true) AuthenticatedUser user) {
        return MemberResponse.of(memberships.getMember(organizationId, membershipId, user));
    }

    @PostMapping("/{membershipId}/suspend")
    @Operation(summary = "Suspend a member (member.manage)")
    public MemberResponse suspend(@PathVariable UUID organizationId, @PathVariable UUID membershipId,
            @Parameter(hidden = true) AuthenticatedUser user) {
        return MemberResponse.of(memberships.suspend(organizationId, membershipId, user));
    }

    @PostMapping("/{membershipId}/reactivate")
    @Operation(summary = "Reactivate a suspended member (member.manage)")
    public MemberResponse reactivate(@PathVariable UUID organizationId, @PathVariable UUID membershipId,
            @Parameter(hidden = true) AuthenticatedUser user) {
        return MemberResponse.of(memberships.reactivate(organizationId, membershipId, user));
    }

    @DeleteMapping("/{membershipId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove a member (member.manage), or leave the organization (own membership)")
    public void remove(@PathVariable UUID organizationId, @PathVariable UUID membershipId,
            @Parameter(hidden = true) AuthenticatedUser user) {
        memberships.remove(organizationId, membershipId, user);
    }

    @PutMapping(path = "/{membershipId}/roles", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Replace a member's roles (member.roles.assign; anti-escalation rules apply)")
    public MemberResponse setRoles(@PathVariable UUID organizationId, @PathVariable UUID membershipId,
            @Parameter(hidden = true) AuthenticatedUser user, @Valid @RequestBody SetRolesRequest request) {
        return MemberResponse.of(memberships.setRoles(organizationId, membershipId, user, request.roleIds()));
    }
}
