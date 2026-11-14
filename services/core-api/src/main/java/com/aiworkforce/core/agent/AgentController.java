package com.aiworkforce.core.agent;

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
import java.util.function.Function;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
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

/** AI agents of an organization: identity and lifecycle (an agent is not a user and not an employee). */
@RestController
@Validated
@RequestMapping(path = "/api/v1/organizations/{organizationId}/agents", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "AI agents", description = "AI employees: identity, lifecycle and configuration (no execution yet)")
public class AgentController {

    static final String HANDLE = "^[a-z0-9][a-z0-9-]{1,39}$";

    @Schema(name = "CreateAgentRequest")
    public record CreateRequest(
            @NotBlank @Size(min = 2, max = 100) String name,
            @Schema(description = "Unique identifier in the organization; derived from the name when omitted.")
            @Pattern(regexp = HANDLE) String handle,
            @Min(1) @Max(8) Integer avatarTone,
            @Size(max = 100) String title,
            @Size(max = 1000) String description,
            UUID departmentId,
            @Size(max = 20) List<@Size(max = 200) String> responsibilities,
            @Size(max = 8000) String systemInstructions) {
    }

    @Schema(name = "UpdateAgentRequest", description = "Omitted fields are left unchanged.")
    public record UpdateRequest(
            @Size(min = 2, max = 100) @Pattern(regexp = ".*\\S.*") String name,
            @Min(1) @Max(8) Integer avatarTone,
            @Size(max = 100) String title,
            @Size(max = 1000) String description,
            @Size(max = 20) List<@Size(max = 200) String> responsibilities,
            @Size(max = 8000) String systemInstructions,
            @Schema(description = "When set, the update is refused (409) if the agent changed meanwhile.")
            Long expectedVersion) {
    }

    @Schema(name = "AssignAgentDepartmentRequest", description = "A null departmentId removes the assignment.")
    public record DepartmentRequest(UUID departmentId) {
    }

    @Schema(name = "AgentHistoryEntry")
    public record HistoryEntry(UUID id, UUID actorUserId, String action, String details, Instant occurredAt) {

        static HistoryEntry of(AuditEntry e) {
            return new HistoryEntry(e.id(), e.actorUserId(), e.action(), e.details(), e.occurredAt());
        }
    }

    private final AgentService agents;

    public AgentController(AgentService agents) {
        this.agents = agents;
    }

    @GetMapping
    @Operation(summary = "List and search AI agents (agent.read)")
    public PageResponse<AgentView> list(@PathVariable UUID organizationId,
            @Parameter(hidden = true) AuthenticatedUser user,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) AgentStatus status,
            @RequestParam(required = false) UUID departmentId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(PageParams.MAX_SIZE) int size) {
        return PageResponse.of(agents.list(organizationId, user, q, status, departmentId,
                PageParams.of(page, size, Sort.by("name", "id"))), Function.identity());
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create an AI agent in DRAFT status (agent.create)")
    public AgentView.Detail create(@PathVariable UUID organizationId, @Parameter(hidden = true) AuthenticatedUser user,
            @Valid @RequestBody CreateRequest request) {
        return agents.create(organizationId, user, new AgentService.NewAgent(request.name(), request.handle(),
                request.avatarTone(), request.title(), request.description(), request.departmentId(),
                request.responsibilities(), request.systemInstructions()));
    }

    @GetMapping("/{agentId}")
    @Operation(summary = "Get an agent with its configuration; system instructions need agent.manage (agent.read)")
    public AgentView.Detail get(@PathVariable UUID organizationId, @PathVariable UUID agentId,
            @Parameter(hidden = true) AuthenticatedUser user) {
        return agents.get(organizationId, agentId, user);
    }

    @PatchMapping(path = "/{agentId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Update the agent identity: name, title, description, responsibilities, instructions (agent.manage)")
    public AgentView.Detail update(@PathVariable UUID organizationId, @PathVariable UUID agentId,
            @Parameter(hidden = true) AuthenticatedUser user, @Valid @RequestBody UpdateRequest request) {
        return agents.update(organizationId, agentId, user, new AgentService.AgentChanges(request.name(),
                request.avatarTone(), request.title(), request.description(), request.responsibilities(),
                request.systemInstructions(), request.expectedVersion()));
    }

    @PutMapping(path = "/{agentId}/department", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Assign or clear the department (agent.manage)")
    public AgentView.Detail changeDepartment(@PathVariable UUID organizationId, @PathVariable UUID agentId,
            @Parameter(hidden = true) AuthenticatedUser user, @RequestBody DepartmentRequest request) {
        return agents.changeDepartment(organizationId, agentId, user, request.departmentId());
    }

    @PostMapping("/{agentId}/activate")
    @Operation(summary = "Activate: needs a tested, valid primary provider. Does not start any execution (agent.activate)")
    public AgentView.Detail activate(@PathVariable UUID organizationId, @PathVariable UUID agentId,
            @Parameter(hidden = true) AuthenticatedUser user) {
        return agents.activate(organizationId, agentId, user);
    }

    @PostMapping("/{agentId}/pause")
    @Operation(summary = "Pause an active agent (agent.activate)")
    public AgentView.Detail pause(@PathVariable UUID organizationId, @PathVariable UUID agentId,
            @Parameter(hidden = true) AuthenticatedUser user) {
        return agents.pause(organizationId, agentId, user);
    }

    @PostMapping("/{agentId}/archive")
    @Operation(summary = "Archive the agent for good: it leaves its teams and is frozen (agent.archive)")
    public AgentView.Detail archive(@PathVariable UUID organizationId, @PathVariable UUID agentId,
            @Parameter(hidden = true) AuthenticatedUser user) {
        return agents.archive(organizationId, agentId, user);
    }

    @GetMapping("/{agentId}/history")
    @Operation(summary = "Configuration history of the agent, newest first (agent.read)")
    public PageResponse<HistoryEntry> history(@PathVariable UUID organizationId, @PathVariable UUID agentId,
            @Parameter(hidden = true) AuthenticatedUser user,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(PageParams.MAX_SIZE) int size) {
        return PageResponse.of(agents.history(organizationId, agentId, user, PageParams.of(page, size, Sort.unsorted())),
                HistoryEntry::of);
    }
}
