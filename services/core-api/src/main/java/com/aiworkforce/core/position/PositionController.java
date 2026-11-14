package com.aiworkforce.core.position;

import com.aiworkforce.core.common.web.PageParams;
import com.aiworkforce.core.common.web.PageResponse;
import com.aiworkforce.core.department.DepartmentRef;
import com.aiworkforce.core.identity.AuthenticatedUser;
import com.aiworkforce.core.workforce.ArchiveStatus;
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

/** Professional positions (job titles). Different from authorization roles. */
@RestController
@Validated
@RequestMapping(path = "/api/v1/organizations/{organizationId}/positions", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Positions", description = "Professional job titles (not authorization roles)")
public class PositionController {

    static final String CODE = "^[A-Za-z0-9][A-Za-z0-9_-]{0,23}$";

    @Schema(name = "Position")
    public record PositionResponse(UUID id, String title, String code, String description, DepartmentRef department,
            ArchiveStatus status, long employeeCount, Instant createdAt, Instant updatedAt, long version) {

        static PositionResponse of(PositionView v) {
            return new PositionResponse(v.id(), v.title(), v.code(), v.description(), v.department(), v.status(),
                    v.employeeCount(), v.createdAt(), v.updatedAt(), v.version());
        }
    }

    @Schema(name = "CreatePositionRequest")
    public record CreateRequest(
            @NotBlank @Size(min = 2, max = 100) String title,
            @NotBlank @Pattern(regexp = CODE) String code,
            @Size(max = 500) String description,
            UUID departmentId) {
    }

    @Schema(name = "UpdatePositionRequest", description = "Omitted fields are left unchanged.")
    public record UpdateRequest(
            @Size(min = 2, max = 100) @Pattern(regexp = ".*\\S.*") String title,
            @Pattern(regexp = CODE) String code,
            @Size(max = 500) String description,
            @Schema(description = "When set, the update is refused (409) if the position changed meanwhile.")
            Long expectedVersion) {
    }

    @Schema(name = "ChangePositionDepartmentRequest", description = "A null departmentId removes the association.")
    public record DepartmentRequest(UUID departmentId) {
    }

    private final PositionService positions;

    public PositionController(PositionService positions) {
        this.positions = positions;
    }

    @GetMapping
    @Operation(summary = "List positions (position.read)")
    public PageResponse<PositionResponse> list(@PathVariable UUID organizationId,
            @Parameter(hidden = true) AuthenticatedUser user,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) ArchiveStatus status,
            @RequestParam(required = false) UUID departmentId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(PageParams.MAX_SIZE) int size) {
        return PageResponse.of(positions.list(organizationId, user, q, status, departmentId,
                PageParams.of(page, size, Sort.by("title", "id"))), PositionResponse::of);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a position (position.manage)")
    public PositionResponse create(@PathVariable UUID organizationId, @Parameter(hidden = true) AuthenticatedUser user,
            @Valid @RequestBody CreateRequest request) {
        return PositionResponse.of(positions.create(organizationId, user, request.title(), request.code(),
                request.description(), request.departmentId()));
    }

    @GetMapping("/{positionId}")
    @Operation(summary = "Get a position (position.read)")
    public PositionResponse get(@PathVariable UUID organizationId, @PathVariable UUID positionId,
            @Parameter(hidden = true) AuthenticatedUser user) {
        return PositionResponse.of(positions.get(organizationId, positionId, user));
    }

    @PatchMapping(path = "/{positionId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Update title, code or description (position.manage)")
    public PositionResponse update(@PathVariable UUID organizationId, @PathVariable UUID positionId,
            @Parameter(hidden = true) AuthenticatedUser user, @Valid @RequestBody UpdateRequest request) {
        return PositionResponse.of(positions.update(organizationId, positionId, user, request.title(), request.code(),
                request.description(), request.expectedVersion()));
    }

    @PutMapping(path = "/{positionId}/department", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Associate the position with a department, or remove the association (position.manage)")
    public PositionResponse changeDepartment(@PathVariable UUID organizationId, @PathVariable UUID positionId,
            @Parameter(hidden = true) AuthenticatedUser user, @RequestBody DepartmentRequest request) {
        return PositionResponse.of(positions.changeDepartment(organizationId, positionId, user, request.departmentId()));
    }

    @PostMapping("/{positionId}/archive")
    @Operation(summary = "Archive a position nobody holds (position.manage)")
    public PositionResponse archive(@PathVariable UUID organizationId, @PathVariable UUID positionId,
            @Parameter(hidden = true) AuthenticatedUser user) {
        return PositionResponse.of(positions.archive(organizationId, positionId, user));
    }

    @PostMapping("/{positionId}/restore")
    @Operation(summary = "Restore an archived position (position.manage)")
    public PositionResponse restore(@PathVariable UUID organizationId, @PathVariable UUID positionId,
            @Parameter(hidden = true) AuthenticatedUser user) {
        return PositionResponse.of(positions.restore(organizationId, positionId, user));
    }

    @DeleteMapping("/{positionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a position that was never held; otherwise archive it (position.manage)")
    public void delete(@PathVariable UUID organizationId, @PathVariable UUID positionId,
            @Parameter(hidden = true) AuthenticatedUser user) {
        positions.delete(organizationId, positionId, user);
    }
}
