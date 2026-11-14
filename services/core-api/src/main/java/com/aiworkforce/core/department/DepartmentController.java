package com.aiworkforce.core.department;

import com.aiworkforce.core.common.web.PageParams;
import com.aiworkforce.core.common.web.PageResponse;
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

/** Departments of an organization (hierarchical, configurable). */
@RestController
@Validated
@RequestMapping(path = "/api/v1/organizations/{organizationId}/departments", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Departments", description = "Organizational units")
public class DepartmentController {

    static final String CODE = "^[A-Za-z0-9][A-Za-z0-9_-]{0,23}$";

    @Schema(name = "Department")
    public record DepartmentResponse(UUID id, String name, String code, String description, UUID parentId,
            EmployeeRef head, ArchiveStatus status, long employeeCount, long childCount, Instant createdAt,
            Instant updatedAt, long version) {

        static DepartmentResponse of(DepartmentView v) {
            return new DepartmentResponse(v.id(), v.name(), v.code(), v.description(), v.parentId(), v.head(),
                    v.status(), v.employeeCount(), v.childCount(), v.createdAt(), v.updatedAt(), v.version());
        }
    }

    @Schema(name = "DepartmentTreeNode")
    public record TreeNodeResponse(DepartmentResponse department, List<TreeNodeResponse> children) {

        static TreeNodeResponse of(DepartmentView.Node node) {
            return new TreeNodeResponse(DepartmentResponse.of(node.department()),
                    node.children().stream().map(TreeNodeResponse::of).toList());
        }
    }

    @Schema(name = "CreateDepartmentRequest")
    public record CreateRequest(
            @NotBlank @Size(min = 2, max = 100) String name,
            @NotBlank @Pattern(regexp = CODE) String code,
            @Size(max = 500) String description,
            UUID parentId,
            UUID headEmployeeId) {
    }

    @Schema(name = "UpdateDepartmentRequest", description = "Omitted fields are left unchanged.")
    public record UpdateRequest(
            @Size(min = 2, max = 100) @Pattern(regexp = ".*\\S.*") String name,
            @Pattern(regexp = CODE) String code,
            @Size(max = 500) String description,
            @Schema(description = "When set, the update is refused (409) if the department changed meanwhile.")
            Long expectedVersion) {
    }

    @Schema(name = "ChangeDepartmentParentRequest", description = "A null parentId moves the department to the top level.")
    public record ParentRequest(UUID parentId) {
    }

    @Schema(name = "AssignDepartmentHeadRequest", description = "A null employeeId removes the head.")
    public record HeadRequest(UUID employeeId) {
    }

    private final DepartmentService departments;

    public DepartmentController(DepartmentService departments) {
        this.departments = departments;
    }

    @GetMapping
    @Operation(summary = "List departments (department.read)")
    public PageResponse<DepartmentResponse> list(@PathVariable UUID organizationId,
            @Parameter(hidden = true) AuthenticatedUser user,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) ArchiveStatus status,
            @RequestParam(required = false) UUID parentId,
            @RequestParam(defaultValue = "false") boolean rootOnly,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(PageParams.MAX_SIZE) int size) {
        return PageResponse.of(departments.list(organizationId, user, q, status, parentId, rootOnly,
                PageParams.of(page, size, Sort.by("name", "id"))), DepartmentResponse::of);
    }

    @GetMapping("/tree")
    @Operation(summary = "Department hierarchy, roots first (department.read)")
    public List<TreeNodeResponse> tree(@PathVariable UUID organizationId,
            @Parameter(hidden = true) AuthenticatedUser user,
            @RequestParam(defaultValue = "false") boolean includeArchived) {
        return departments.tree(organizationId, user, includeArchived).stream().map(TreeNodeResponse::of).toList();
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a department (department.manage)")
    public DepartmentResponse create(@PathVariable UUID organizationId, @Parameter(hidden = true) AuthenticatedUser user,
            @Valid @RequestBody CreateRequest request) {
        return DepartmentResponse.of(departments.create(organizationId, user, request.name(), request.code(),
                request.description(), request.parentId(), request.headEmployeeId()));
    }

    @GetMapping("/{departmentId}")
    @Operation(summary = "Get a department (department.read)")
    public DepartmentResponse get(@PathVariable UUID organizationId, @PathVariable UUID departmentId,
            @Parameter(hidden = true) AuthenticatedUser user) {
        return DepartmentResponse.of(departments.get(organizationId, departmentId, user));
    }

    @PatchMapping(path = "/{departmentId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Update name, code or description (department.manage)")
    public DepartmentResponse update(@PathVariable UUID organizationId, @PathVariable UUID departmentId,
            @Parameter(hidden = true) AuthenticatedUser user, @Valid @RequestBody UpdateRequest request) {
        return DepartmentResponse.of(departments.update(organizationId, departmentId, user, request.name(),
                request.code(), request.description(), request.expectedVersion()));
    }

    @PutMapping(path = "/{departmentId}/parent", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Change the parent department; cycles are refused (department.manage)")
    public DepartmentResponse changeParent(@PathVariable UUID organizationId, @PathVariable UUID departmentId,
            @Parameter(hidden = true) AuthenticatedUser user, @RequestBody ParentRequest request) {
        return DepartmentResponse.of(departments.changeParent(organizationId, departmentId, user, request.parentId()));
    }

    @PutMapping(path = "/{departmentId}/head", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Assign or remove the department head (department.manage)")
    public DepartmentResponse assignHead(@PathVariable UUID organizationId, @PathVariable UUID departmentId,
            @Parameter(hidden = true) AuthenticatedUser user, @RequestBody HeadRequest request) {
        return DepartmentResponse.of(departments.assignHead(organizationId, departmentId, user, request.employeeId()));
    }

    @PostMapping("/{departmentId}/archive")
    @Operation(summary = "Archive a department without employees or active sub-departments (department.manage)")
    public DepartmentResponse archive(@PathVariable UUID organizationId, @PathVariable UUID departmentId,
            @Parameter(hidden = true) AuthenticatedUser user) {
        return DepartmentResponse.of(departments.archive(organizationId, departmentId, user));
    }

    @PostMapping("/{departmentId}/restore")
    @Operation(summary = "Restore an archived department (department.manage)")
    public DepartmentResponse restore(@PathVariable UUID organizationId, @PathVariable UUID departmentId,
            @Parameter(hidden = true) AuthenticatedUser user) {
        return DepartmentResponse.of(departments.restore(organizationId, departmentId, user));
    }

    @DeleteMapping("/{departmentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a department that was never used; otherwise archive it (department.manage)")
    public void delete(@PathVariable UUID organizationId, @PathVariable UUID departmentId,
            @Parameter(hidden = true) AuthenticatedUser user) {
        departments.delete(organizationId, departmentId, user);
    }
}
