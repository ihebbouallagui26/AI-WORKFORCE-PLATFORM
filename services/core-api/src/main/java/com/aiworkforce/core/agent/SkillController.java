package com.aiworkforce.core.agent;

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
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Skills of an organization and the global tool registry. */
@RestController
@Validated
@RequestMapping(path = "/api/v1/organizations/{organizationId}", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Skills and tools", description = "Reusable skills and the tool registry (metadata, no execution)")
public class SkillController {

    @Schema(name = "Skill")
    public record SkillResponse(UUID id, String name, String category, String description, boolean active,
            java.time.Instant createdAt, java.time.Instant updatedAt, long version) {

        static SkillResponse of(SkillService.SkillView v) {
            return new SkillResponse(v.id(), v.name(), v.category(), v.description(), v.active(), v.createdAt(),
                    v.updatedAt(), v.version());
        }
    }

    @Schema(name = "CreateSkillRequest")
    public record CreateRequest(
            @NotBlank @Size(min = 2, max = 60) String name,
            @Size(max = 40) String category,
            @Size(max = 300) String description) {
    }

    @Schema(name = "UpdateSkillRequest", description = "Omitted fields are left unchanged.")
    public record UpdateRequest(
            @Size(min = 2, max = 60) @Pattern(regexp = ".*\\S.*") String name,
            @Size(max = 40) String category,
            @Size(max = 300) String description,
            Boolean active,
            Long expectedVersion) {
    }

    private final SkillService skills;
    private final AgentConfigurationService configuration;

    public SkillController(SkillService skills, AgentConfigurationService configuration) {
        this.skills = skills;
        this.configuration = configuration;
    }

    @GetMapping("/skills")
    @Operation(summary = "List skills (skill.read)")
    public PageResponse<SkillResponse> list(@PathVariable UUID organizationId,
            @Parameter(hidden = true) AuthenticatedUser user,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) @Size(max = 40) String category,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(PageParams.MAX_SIZE) int size) {
        return PageResponse.of(skills.list(organizationId, user, q, active, category,
                PageParams.of(page, size, Sort.by("name", "id"))), SkillResponse::of);
    }

    @PostMapping(path = "/skills", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a skill (skill.manage)")
    public SkillResponse create(@PathVariable UUID organizationId, @Parameter(hidden = true) AuthenticatedUser user,
            @Valid @RequestBody CreateRequest request) {
        return SkillResponse.of(skills.create(organizationId, user, request.name(), request.category(),
                request.description()));
    }

    @PatchMapping(path = "/skills/{skillId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Update or (de)activate a skill (skill.manage)")
    public SkillResponse update(@PathVariable UUID organizationId, @PathVariable UUID skillId,
            @Parameter(hidden = true) AuthenticatedUser user, @Valid @RequestBody UpdateRequest request) {
        return SkillResponse.of(skills.update(organizationId, skillId, user, request.name(), request.category(),
                request.description(), request.active(), request.expectedVersion()));
    }

    @DeleteMapping("/skills/{skillId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a skill assigned to no agent; otherwise deactivate it (skill.manage)")
    public void delete(@PathVariable UUID organizationId, @PathVariable UUID skillId,
            @Parameter(hidden = true) AuthenticatedUser user) {
        skills.delete(organizationId, skillId, user);
    }

    @GetMapping("/tools")
    @Operation(summary = "The tool registry: metadata for future MCP integration, nothing executable (agent.read)")
    public List<AgentView.ToolView> tools(@PathVariable UUID organizationId,
            @Parameter(hidden = true) AuthenticatedUser user) {
        return configuration.toolCatalog(organizationId, user);
    }
}
