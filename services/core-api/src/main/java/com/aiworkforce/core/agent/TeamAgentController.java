package com.aiworkforce.core.agent;

import com.aiworkforce.core.identity.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** AI agents of a team, next to its human members (a separate relationship). */
@RestController
@Validated
@RequestMapping(path = "/api/v1/organizations/{organizationId}/teams/{teamId}/agents", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Team agents", description = "AI agents participating in teams")
public class TeamAgentController {

    @Schema(name = "TeamAgent")
    public record TeamAgentResponse(AgentView agent, Instant joinedAt, Instant leftAt) {

        static TeamAgentResponse of(TeamAgentService.TeamAgentView v) {
            return new TeamAgentResponse(v.agent(), v.joinedAt(), v.leftAt());
        }
    }

    @Schema(name = "AddTeamAgentRequest")
    public record AddRequest(@NotNull UUID agentId) {
    }

    private final TeamAgentService teamAgents;

    public TeamAgentController(TeamAgentService teamAgents) {
        this.teamAgents = teamAgents;
    }

    @GetMapping
    @Operation(summary = "AI agents of the team, optionally with former ones (agent.read)")
    public List<TeamAgentResponse> list(@PathVariable UUID organizationId, @PathVariable UUID teamId,
            @Parameter(hidden = true) AuthenticatedUser user,
            @RequestParam(defaultValue = "false") boolean includeFormer) {
        return teamAgents.list(organizationId, teamId, user, includeFormer).stream().map(TeamAgentResponse::of).toList();
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add an agent to the team; archived agents and teams are refused (team.manage + agent.read)")
    public TeamAgentResponse add(@PathVariable UUID organizationId, @PathVariable UUID teamId,
            @Parameter(hidden = true) AuthenticatedUser user, @Valid @RequestBody AddRequest request) {
        return TeamAgentResponse.of(teamAgents.add(organizationId, teamId, user, request.agentId()));
    }

    @DeleteMapping("/{agentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove an agent from the team; history is kept (team.manage)")
    public void remove(@PathVariable UUID organizationId, @PathVariable UUID teamId, @PathVariable UUID agentId,
            @Parameter(hidden = true) AuthenticatedUser user) {
        teamAgents.remove(organizationId, teamId, user, agentId);
    }
}
