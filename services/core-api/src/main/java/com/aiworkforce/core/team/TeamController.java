package com.aiworkforce.core.team;

import com.aiworkforce.core.common.web.PageParams;
import com.aiworkforce.core.common.web.PageResponse;
import com.aiworkforce.core.department.DepartmentRef;
import com.aiworkforce.core.identity.AuthenticatedUser;
import com.aiworkforce.core.workforce.ArchiveStatus;
import com.aiworkforce.core.workforce.EmployeeDirectory.EmployeeRef;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Teams of an organization and their human members. */
@RestController
@Validated
@RequestMapping(path = "/api/v1/organizations/{organizationId}", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Teams", description = "Collaborative groups of employees")
public class TeamController {

    @Schema(name = "Team")
    public record TeamResponse(UUID id, String name, String description, DepartmentRef department, EmployeeRef leader,
            ArchiveStatus status, long memberCount, long agentCount, Instant createdAt, Instant updatedAt,
            long version) {

        static TeamResponse of(TeamView v) {
            return new TeamResponse(v.id(), v.name(), v.description(), v.department(), v.leader(), v.status(),
                    v.memberCount(), v.agentCount(), v.createdAt(), v.updatedAt(), v.version());
        }
    }

    @Schema(name = "TeamMember")
    public record MemberResponse(EmployeeRef employee, Instant joinedAt, Instant leftAt, boolean leader) {

        static MemberResponse of(TeamView.Member m) {
            return new MemberResponse(m.employee(), m.joinedAt(), m.leftAt(), m.leader());
        }
    }

    @Schema(name = "TeamDetail")
    public record DetailResponse(TeamResponse team, List<MemberResponse> members) {
    }

    @Schema(name = "CreateTeamRequest",
            description = "When a leader is given, the leader is added as the first member.")
    public record CreateRequest(
            @NotBlank @Size(min = 2, max = 100) String name,
            @Size(max = 500) String description,
            UUID departmentId,
            UUID leaderEmployeeId) {
    }

    @Schema(name = "UpdateTeamRequest", description = "Omitted fields are left unchanged.")
    public record UpdateRequest(
            @Size(min = 2, max = 100) @Pattern(regexp = ".*\\S.*") String name,
            @Size(max = 500) String description,
            @Schema(description = "When set, the update is refused (409) if the team changed meanwhile.")
            Long expectedVersion) {
    }

    @Schema(name = "ChangeTeamDepartmentRequest", description = "A null departmentId removes the association.")
    public record DepartmentRequest(UUID departmentId) {
    }

    @Schema(name = "AssignTeamLeaderRequest", description = "The leader must be a current member. Null clears it.")
    public record LeaderRequest(UUID employeeId) {
    }

    @Schema(name = "AddTeamMemberRequest")
    public record AddMemberRequest(@NotNull UUID employeeId) {
    }

    private final TeamService teams;

    public TeamController(TeamService teams) {
        this.teams = teams;
    }

    @GetMapping("/teams")
    @Operation(summary = "List teams (team.read)")
    public PageResponse<TeamResponse> list(@PathVariable UUID organizationId,
            @Parameter(hidden = true) AuthenticatedUser user,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) ArchiveStatus status,
            @RequestParam(required = false) UUID departmentId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(PageParams.MAX_SIZE) int size) {
        return PageResponse.of(teams.list(organizationId, user, q, status, departmentId,
                PageParams.of(page, size, Sort.by("name", "id"))), TeamResponse::of);
    }

    @PostMapping(path = "/teams", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a team (team.manage)")
    public TeamResponse create(@PathVariable UUID organizationId, @Parameter(hidden = true) AuthenticatedUser user,
            @Valid @RequestBody CreateRequest request) {
        return TeamResponse.of(teams.create(organizationId, user, request.name(), request.description(),
                request.departmentId(), request.leaderEmployeeId()));
    }

    @GetMapping("/teams/{teamId}")
    @Operation(summary = "Get a team with its current members (team.read)")
    public DetailResponse get(@PathVariable UUID organizationId, @PathVariable UUID teamId,
            @Parameter(hidden = true) AuthenticatedUser user) {
        TeamView.Detail detail = teams.get(organizationId, teamId, user);
        return new DetailResponse(TeamResponse.of(detail.team()),
                detail.members().stream().map(MemberResponse::of).toList());
    }

    @PatchMapping(path = "/teams/{teamId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Update name or description (team.manage)")
    public TeamResponse update(@PathVariable UUID organizationId, @PathVariable UUID teamId,
            @Parameter(hidden = true) AuthenticatedUser user, @Valid @RequestBody UpdateRequest request) {
        return TeamResponse.of(teams.update(organizationId, teamId, user, request.name(), request.description(),
                request.expectedVersion()));
    }

    @PutMapping(path = "/teams/{teamId}/department", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Associate the team with a department, or remove the association (team.manage)")
    public TeamResponse changeDepartment(@PathVariable UUID organizationId, @PathVariable UUID teamId,
            @Parameter(hidden = true) AuthenticatedUser user, @RequestBody DepartmentRequest request) {
        return TeamResponse.of(teams.changeDepartment(organizationId, teamId, user, request.departmentId()));
    }

    @PutMapping(path = "/teams/{teamId}/leader", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Assign or clear the team leader, who must be a current member (team.manage)")
    public TeamResponse assignLeader(@PathVariable UUID organizationId, @PathVariable UUID teamId,
            @Parameter(hidden = true) AuthenticatedUser user, @RequestBody LeaderRequest request) {
        return TeamResponse.of(teams.assignLeader(organizationId, teamId, user, request.employeeId()));
    }

    @PostMapping("/teams/{teamId}/archive")
    @Operation(summary = "Archive a team (team.manage)")
    public TeamResponse archive(@PathVariable UUID organizationId, @PathVariable UUID teamId,
            @Parameter(hidden = true) AuthenticatedUser user) {
        return TeamResponse.of(teams.archive(organizationId, teamId, user));
    }

    @PostMapping("/teams/{teamId}/restore")
    @Operation(summary = "Restore an archived team (team.manage)")
    public TeamResponse restore(@PathVariable UUID organizationId, @PathVariable UUID teamId,
            @Parameter(hidden = true) AuthenticatedUser user) {
        return TeamResponse.of(teams.restore(organizationId, teamId, user));
    }

    @GetMapping("/teams/{teamId}/members")
    @Operation(summary = "List the team members, optionally with former members (team.read)")
    public List<MemberResponse> members(@PathVariable UUID organizationId, @PathVariable UUID teamId,
            @Parameter(hidden = true) AuthenticatedUser user,
            @RequestParam(defaultValue = "false") boolean includeFormer) {
        return teams.members(organizationId, teamId, user, includeFormer).stream().map(MemberResponse::of).toList();
    }

    @PostMapping(path = "/teams/{teamId}/members", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add a current employee to the team; duplicates are refused (team.manage)")
    public MemberResponse addMember(@PathVariable UUID organizationId, @PathVariable UUID teamId,
            @Parameter(hidden = true) AuthenticatedUser user, @Valid @RequestBody AddMemberRequest request) {
        return MemberResponse.of(teams.addMember(organizationId, teamId, user, request.employeeId()));
    }

    @DeleteMapping("/teams/{teamId}/members/{employeeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove an employee from the team; the leader must be replaced first (team.manage)")
    public void removeMember(@PathVariable UUID organizationId, @PathVariable UUID teamId,
            @PathVariable UUID employeeId, @Parameter(hidden = true) AuthenticatedUser user) {
        teams.removeMember(organizationId, teamId, user, employeeId);
    }

    @GetMapping("/employees/{employeeId}/teams")
    @Operation(summary = "Teams an employee currently belongs to (team.read)")
    public List<TeamResponse> teamsOfEmployee(@PathVariable UUID organizationId, @PathVariable UUID employeeId,
            @Parameter(hidden = true) AuthenticatedUser user) {
        return teams.teamsOfEmployee(organizationId, employeeId, user).stream().map(TeamResponse::of).toList();
    }
}
