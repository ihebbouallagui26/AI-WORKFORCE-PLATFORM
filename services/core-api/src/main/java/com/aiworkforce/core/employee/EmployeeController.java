package com.aiworkforce.core.employee;

import com.aiworkforce.core.access.Membership;
import com.aiworkforce.core.common.web.PageParams;
import com.aiworkforce.core.common.web.PageResponse;
import com.aiworkforce.core.department.DepartmentRef;
import com.aiworkforce.core.identity.AuthenticatedUser;
import com.aiworkforce.core.position.PositionRef;
import com.aiworkforce.core.workforce.EmployeeDirectory.EmployeeRef;
import com.aiworkforce.core.workforce.EmploymentStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
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

/** Human employee profiles of an organization. */
@RestController
@Validated
@RequestMapping(path = "/api/v1/organizations/{organizationId}/employees", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Employees", description = "Employee profiles (professional identity, separate from platform access)")
public class EmployeeController {

    @Schema(name = "Employee")
    public record EmployeeResponse(UUID id, UUID membershipId, Membership.Status membershipStatus,
            String employeeNumber, String displayName, String professionalEmail, PositionRef position,
            DepartmentRef department, EmployeeRef manager, EmploymentStatus status, LocalDate startDate,
            LocalDate endDate, String description, long directReportCount, Instant createdAt, Instant updatedAt,
            long version) {

        static EmployeeResponse of(EmployeeView v) {
            return new EmployeeResponse(v.id(), v.membershipId(), v.membershipStatus(), v.employeeNumber(),
                    v.displayName(), v.professionalEmail(), v.position(), v.department(), v.manager(), v.status(),
                    v.startDate(), v.endDate(), v.description(), v.directReportCount(), v.createdAt(), v.updatedAt(),
                    v.version());
        }
    }

    @Schema(name = "CreateEmployeeRequest")
    public record CreateRequest(
            @NotBlank @Size(max = 200) String displayName,
            @Size(max = 50) String employeeNumber,
            @Email @Size(max = 254) String professionalEmail,
            @Schema(description = "Optional platform membership of this organization to link to the profile.")
            UUID membershipId,
            UUID positionId,
            UUID departmentId,
            UUID managerId,
            @Schema(description = "Defaults to today (UTC).") LocalDate startDate,
            @Size(max = 1000) String description) {
    }

    @Schema(name = "UpdateEmployeeRequest",
            description = "Omitted fields are left unchanged; an empty employeeNumber or professionalEmail clears it.")
    public record UpdateRequest(
            @Size(max = 200) @Pattern(regexp = ".*\\S.*") String displayName,
            @Size(max = 50) String employeeNumber,
            @Email @Size(max = 254) String professionalEmail,
            LocalDate startDate,
            @Size(max = 1000) String description,
            @Schema(description = "When set, the update is refused (409) if the employee changed meanwhile.")
            Long expectedVersion) {
    }

    @Schema(name = "AssignEmployeeDepartmentRequest", description = "A null departmentId removes the assignment.")
    public record DepartmentRequest(UUID departmentId) {
    }

    @Schema(name = "AssignEmployeePositionRequest", description = "A null positionId removes the assignment.")
    public record PositionRequest(UUID positionId) {
    }

    @Schema(name = "AssignEmployeeManagerRequest", description = "A null managerId removes the manager.")
    public record ManagerRequest(UUID managerId) {
    }

    @Schema(name = "LinkEmployeeMembershipRequest", description = "A null membershipId unlinks the platform account.")
    public record MembershipRequest(UUID membershipId) {
    }

    @Schema(name = "ChangeEmploymentStatusRequest")
    public record StatusRequest(@NotNull EmploymentStatus status) {
    }

    private final EmployeeService employees;

    public EmployeeController(EmployeeService employees) {
        this.employees = employees;
    }

    @GetMapping
    @Operation(summary = "List and search employees (employee.read)")
    public PageResponse<EmployeeResponse> list(@PathVariable UUID organizationId,
            @Parameter(hidden = true) AuthenticatedUser user,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) EmploymentStatus status,
            @RequestParam(required = false) UUID departmentId,
            @RequestParam(required = false) UUID positionId,
            @RequestParam(required = false) UUID managerId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(PageParams.MAX_SIZE) int size) {
        return PageResponse.of(employees.list(organizationId, user, q, status, departmentId, positionId, managerId,
                PageParams.of(page, size, Sort.by("displayName", "id"))), EmployeeResponse::of);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create an employee profile, optionally linked to a membership (employee.create)")
    public EmployeeResponse create(@PathVariable UUID organizationId, @Parameter(hidden = true) AuthenticatedUser user,
            @Valid @RequestBody CreateRequest request) {
        return EmployeeResponse.of(employees.create(organizationId, user, new EmployeeService.NewEmployee(
                request.displayName(), request.employeeNumber(), request.professionalEmail(), request.membershipId(),
                request.positionId(), request.departmentId(), request.managerId(), request.startDate(),
                request.description())));
    }

    @GetMapping("/{employeeId}")
    @Operation(summary = "Get an employee profile (employee.read)")
    public EmployeeResponse get(@PathVariable UUID organizationId, @PathVariable UUID employeeId,
            @Parameter(hidden = true) AuthenticatedUser user) {
        return EmployeeResponse.of(employees.get(organizationId, employeeId, user));
    }

    @PatchMapping(path = "/{employeeId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Update the scalar fields of an employee (employee.manage)")
    public EmployeeResponse update(@PathVariable UUID organizationId, @PathVariable UUID employeeId,
            @Parameter(hidden = true) AuthenticatedUser user, @Valid @RequestBody UpdateRequest request) {
        return EmployeeResponse.of(employees.update(organizationId, employeeId, user, request.displayName(),
                request.employeeNumber(), request.professionalEmail(), request.startDate(), request.description(),
                request.expectedVersion()));
    }

    @PutMapping(path = "/{employeeId}/department", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Assign or clear the department (employee.manage)")
    public EmployeeResponse assignDepartment(@PathVariable UUID organizationId, @PathVariable UUID employeeId,
            @Parameter(hidden = true) AuthenticatedUser user, @RequestBody DepartmentRequest request) {
        return EmployeeResponse.of(employees.assignDepartment(organizationId, employeeId, user,
                request.departmentId()));
    }

    @PutMapping(path = "/{employeeId}/position", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Assign or clear the position; never changes permissions (employee.manage)")
    public EmployeeResponse assignPosition(@PathVariable UUID organizationId, @PathVariable UUID employeeId,
            @Parameter(hidden = true) AuthenticatedUser user, @RequestBody PositionRequest request) {
        return EmployeeResponse.of(employees.assignPosition(organizationId, employeeId, user, request.positionId()));
    }

    @PutMapping(path = "/{employeeId}/manager", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Assign or clear the direct manager; reporting cycles are refused (employee.manage)")
    public EmployeeResponse assignManager(@PathVariable UUID organizationId, @PathVariable UUID employeeId,
            @Parameter(hidden = true) AuthenticatedUser user, @RequestBody ManagerRequest request) {
        return EmployeeResponse.of(employees.assignManager(organizationId, employeeId, user, request.managerId()));
    }

    @PutMapping(path = "/{employeeId}/membership", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Link or unlink the platform membership of the same organization (employee.manage)")
    public EmployeeResponse linkMembership(@PathVariable UUID organizationId, @PathVariable UUID employeeId,
            @Parameter(hidden = true) AuthenticatedUser user, @RequestBody MembershipRequest request) {
        return EmployeeResponse.of(employees.linkMembership(organizationId, employeeId, user,
                request.membershipId()));
    }

    @PutMapping(path = "/{employeeId}/status", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Change the employment status; to or from INACTIVE needs employee.deactivate")
    public EmployeeResponse changeStatus(@PathVariable UUID organizationId, @PathVariable UUID employeeId,
            @Parameter(hidden = true) AuthenticatedUser user, @Valid @RequestBody StatusRequest request) {
        return EmployeeResponse.of(employees.changeStatus(organizationId, employeeId, user, request.status()));
    }
}
