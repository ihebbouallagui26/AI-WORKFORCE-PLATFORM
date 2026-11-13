package com.aiworkforce.core.employee;

import com.aiworkforce.core.access.MembershipService;
import com.aiworkforce.core.access.MembershipService.MemberLink;
import com.aiworkforce.core.access.OrganizationAccess;
import com.aiworkforce.core.access.OrganizationLock;
import com.aiworkforce.core.access.Permission;
import com.aiworkforce.core.access.TenantAccessService;
import com.aiworkforce.core.audit.AuditLog;
import com.aiworkforce.core.common.exception.BadRequestException;
import com.aiworkforce.core.common.exception.ConflictException;
import com.aiworkforce.core.common.exception.ResourceNotFoundException;
import com.aiworkforce.core.common.persistence.LikePatterns;
import com.aiworkforce.core.department.DepartmentDirectory;
import com.aiworkforce.core.department.DepartmentRef;
import com.aiworkforce.core.identity.AuthenticatedUser;
import com.aiworkforce.core.position.PositionDirectory;
import com.aiworkforce.core.position.PositionRef;
import com.aiworkforce.core.workforce.EmployeeDirectory.EmployeeRef;
import com.aiworkforce.core.workforce.EmployeeLifecycleParticipant;
import com.aiworkforce.core.workforce.EmploymentStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Employee profiles. Every operation starts with the tenant check (active membership + permission)
 * and only touches rows of that organization. Employees are never deleted: they are deactivated,
 * so history (team membership, reporting lines, audit) stays consistent.
 *
 * <p>Changing a position, department or manager never changes anyone's authorization: employee
 * data and RBAC are independent.
 */
@Service
public class EmployeeService {

    /** Bound on the length of a reporting chain walked to detect cycles. */
    private static final int MAX_CHAIN = 1000;

    /** Data of a new employee profile. */
    public record NewEmployee(String displayName, String employeeNumber, String professionalEmail,
            UUID membershipId, UUID positionId, UUID departmentId, UUID managerId, LocalDate startDate,
            String description) {
    }

    private final EmployeeRepository employees;
    private final TenantAccessService access;
    private final OrganizationLock lock;
    private final MembershipService memberships;
    private final DepartmentDirectory departments;
    private final PositionDirectory positions;
    private final List<EmployeeLifecycleParticipant> participants;
    private final AuditLog audit;
    private final Clock clock;

    public EmployeeService(EmployeeRepository employees, TenantAccessService access, OrganizationLock lock,
            MembershipService memberships, DepartmentDirectory departments, PositionDirectory positions,
            List<EmployeeLifecycleParticipant> participants, AuditLog audit, Clock clock) {
        this.employees = employees;
        this.access = access;
        this.lock = lock;
        this.memberships = memberships;
        this.departments = departments;
        this.positions = positions;
        this.participants = participants;
        this.audit = audit;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ queries

    @Transactional(readOnly = true)
    public Page<EmployeeView> list(UUID organizationId, AuthenticatedUser user, String query, EmploymentStatus status,
            UUID departmentId, UUID positionId, UUID managerId, Pageable pageable) {
        access.require(organizationId, user, Permission.EMPLOYEE_READ);
        Page<EmployeeProfile> page = employees.searchByOrganization(organizationId, status, departmentId, positionId,
                managerId, LikePatterns.contains(query), pageable);
        Map<UUID, EmployeeView> views = views(organizationId, page.getContent()).stream()
                .collect(Collectors.toMap(EmployeeView::id, v -> v));
        return page.map(e -> views.get(e.getId()));
    }

    @Transactional(readOnly = true)
    public EmployeeView get(UUID organizationId, UUID employeeId, AuthenticatedUser user) {
        access.require(organizationId, user, Permission.EMPLOYEE_READ);
        return view(organizationId, load(organizationId, employeeId));
    }

    // ------------------------------------------------------------------ commands

    @Transactional
    public EmployeeView create(UUID organizationId, AuthenticatedUser user, NewEmployee data) {
        access.require(organizationId, user, Permission.EMPLOYEE_CREATE);
        lock.acquire(organizationId);
        Instant now = Instant.now(clock);
        LocalDate today = LocalDate.now(clock);
        EmployeeProfile employee = new EmployeeProfile(organizationId, data.displayName().trim(),
                data.startDate() == null ? today : data.startDate(), now);

        String number = blankToNull(data.employeeNumber());
        if (number != null && employees.existsByOrganizationIdAndEmployeeNumber(organizationId, number)) {
            throw new ConflictException("An employee with this number already exists.");
        }
        employee.updateDetails(employee.getDisplayName(), number, normalizeEmail(data.professionalEmail()),
                employee.getStartDate(), data.description() == null ? "" : data.description().trim(), now);
        if (data.membershipId() != null) {
            requireLinkable(organizationId, data.membershipId(), null);
            employee.linkMembership(data.membershipId(), now);
        }
        if (data.departmentId() != null) {
            departments.requireActive(organizationId, data.departmentId());
            employee.assignDepartment(data.departmentId(), now);
        }
        if (data.positionId() != null) {
            positions.requireActive(organizationId, data.positionId());
            employee.assignPosition(data.positionId(), now);
        }
        if (data.managerId() != null) {
            requireCurrentEmployee(organizationId, data.managerId());
            employee.assignManager(data.managerId(), now);
        }
        employees.save(employee);
        employees.flush();
        audit.record(organizationId, user.userId(), "employee.created", "employee", employee.getId(),
                details("displayName", employee.getDisplayName(), "membershipId", data.membershipId(),
                        "departmentId", data.departmentId(), "positionId", data.positionId(),
                        "managerId", data.managerId()));
        return view(organizationId, employee);
    }

    /** Scalar fields only; null means unchanged and an empty string clears number/e-mail. */
    @Transactional
    public EmployeeView update(UUID organizationId, UUID employeeId, AuthenticatedUser user, String displayName,
            String employeeNumber, String professionalEmail, LocalDate startDate, String description,
            Long expectedVersion) {
        access.require(organizationId, user, Permission.EMPLOYEE_MANAGE);
        EmployeeProfile employee = load(organizationId, employeeId);
        if (expectedVersion != null && expectedVersion != employee.getVersion()) {
            throw new ConflictException("This employee was modified by someone else. Reload and retry.");
        }
        String nextNumber = employeeNumber == null ? employee.getEmployeeNumber() : blankToNull(employeeNumber);
        if (nextNumber != null && !nextNumber.equals(employee.getEmployeeNumber())
                && employees.existsByOrganizationIdAndEmployeeNumberAndIdNot(organizationId, nextNumber, employeeId)) {
            throw new ConflictException("An employee with this number already exists.");
        }
        LocalDate nextStart = startDate == null ? employee.getStartDate() : startDate;
        if (employee.getEndDate() != null && employee.getEndDate().isBefore(nextStart)) {
            throw new BadRequestException("The start date cannot be after the end date.");
        }
        employee.updateDetails(displayName == null ? employee.getDisplayName() : displayName.trim(), nextNumber,
                professionalEmail == null ? employee.getProfessionalEmail() : normalizeEmail(professionalEmail),
                nextStart, description == null ? employee.getDescription() : description.trim(), Instant.now(clock));
        employees.flush();
        audit.record(organizationId, user.userId(), "employee.updated", "employee", employeeId,
                details("displayName", employee.getDisplayName()));
        return view(organizationId, employee);
    }

    @Transactional
    public EmployeeView assignDepartment(UUID organizationId, UUID employeeId, AuthenticatedUser user,
            UUID departmentId) {
        access.require(organizationId, user, Permission.EMPLOYEE_MANAGE);
        EmployeeProfile employee = load(organizationId, employeeId);
        if (departmentId != null) {
            departments.requireActive(organizationId, departmentId);
        }
        employee.assignDepartment(departmentId, Instant.now(clock));
        employees.flush();
        audit.record(organizationId, user.userId(), "employee.department.assigned", "employee", employeeId,
                details("departmentId", departmentId));
        return view(organizationId, employee);
    }

    @Transactional
    public EmployeeView assignPosition(UUID organizationId, UUID employeeId, AuthenticatedUser user,
            UUID positionId) {
        access.require(organizationId, user, Permission.EMPLOYEE_MANAGE);
        EmployeeProfile employee = load(organizationId, employeeId);
        if (positionId != null) {
            positions.requireActive(organizationId, positionId);
        }
        employee.assignPosition(positionId, Instant.now(clock));
        employees.flush();
        audit.record(organizationId, user.userId(), "employee.position.assigned", "employee", employeeId,
                details("positionId", positionId));
        return view(organizationId, employee);
    }

    /** Sets the direct manager. Reporting cycles are refused. */
    @Transactional
    public EmployeeView assignManager(UUID organizationId, UUID employeeId, AuthenticatedUser user, UUID managerId) {
        access.require(organizationId, user, Permission.EMPLOYEE_MANAGE);
        lock.acquire(organizationId);
        EmployeeProfile employee = load(organizationId, employeeId);
        if (managerId != null) {
            if (managerId.equals(employeeId)) {
                throw new ConflictException("An employee cannot be their own manager.");
            }
            requireCurrentEmployee(organizationId, managerId);
            if (reportsTo(organizationId, managerId, employeeId)) {
                throw new ConflictException("This would create a reporting cycle.");
            }
        }
        employee.assignManager(managerId, Instant.now(clock));
        employees.flush();
        audit.record(organizationId, user.userId(), "employee.manager.assigned", "employee", employeeId,
                details("managerId", managerId));
        return view(organizationId, employee);
    }

    /** Links the profile to a platform membership of the same organization, or unlinks it (null). */
    @Transactional
    public EmployeeView linkMembership(UUID organizationId, UUID employeeId, AuthenticatedUser user,
            UUID membershipId) {
        access.require(organizationId, user, Permission.EMPLOYEE_MANAGE);
        EmployeeProfile employee = load(organizationId, employeeId);
        if (membershipId != null) {
            requireLinkable(organizationId, membershipId, employeeId);
        }
        employee.linkMembership(membershipId, Instant.now(clock));
        employees.flush();
        audit.record(organizationId, user.userId(), "employee.membership.linked", "employee", employeeId,
                details("membershipId", membershipId));
        return view(organizationId, employee);
    }

    /**
     * Changes the employment status. Deactivation (and reactivation) needs
     * {@code employee.deactivate}; ACTIVE ⇄ ON_LEAVE needs {@code employee.manage}.
     */
    @Transactional
    public EmployeeView changeStatus(UUID organizationId, UUID employeeId, AuthenticatedUser user,
            EmploymentStatus target) {
        // Membership and a status-related permission are checked BEFORE taking the organization
        // lock, so someone without any right cannot make others wait.
        OrganizationAccess actor = access.requireMember(organizationId, user);
        if (!actor.has(Permission.EMPLOYEE_MANAGE) && !actor.has(Permission.EMPLOYEE_DEACTIVATE)) {
            throw new AccessDeniedException("Missing permission " + Permission.EMPLOYEE_MANAGE.code());
        }
        lock.acquire(organizationId);
        EmployeeProfile employee = load(organizationId, employeeId);
        EmploymentStatus current = employee.getStatus();
        boolean touchesInactive = current == EmploymentStatus.INACTIVE || target == EmploymentStatus.INACTIVE;
        actor.require(touchesInactive ? Permission.EMPLOYEE_DEACTIVATE : Permission.EMPLOYEE_MANAGE);
        if (current == target) {
            return view(organizationId, employee);
        }
        Instant now = Instant.now(clock);
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("from", current.name());
        changes.put("to", target.name());
        if (target == EmploymentStatus.INACTIVE) {
            if (employees.countByOrganizationIdAndManagerIdAndStatusNot(organizationId, employeeId,
                    EmploymentStatus.INACTIVE) > 0) {
                throw new ConflictException("This employee manages other employees. Reassign them first.");
            }
            participants.forEach(p -> p.beforeDeactivation(organizationId, employeeId));
            employee.changeStatus(target, LocalDate.now(clock), now);
            employees.flush();
            participants.forEach(p -> p.afterDeactivation(organizationId, employeeId));
        } else if (current == EmploymentStatus.INACTIVE) {
            reactivate(organizationId, employee, target, now, changes);
        } else {
            employee.changeStatus(target, employee.getEndDate(), now);
        }
        employees.flush();
        audit.record(organizationId, user.userId(), "employee.status.changed", "employee", employeeId, changes);
        return view(organizationId, employee);
    }

    private void reactivate(UUID organizationId, EmployeeProfile employee, EmploymentStatus target, Instant now,
            Map<String, Object> changes) {
        if (employee.getDepartmentId() != null) {
            departments.requireActive(organizationId, employee.getDepartmentId());
        }
        if (employee.getPositionId() != null) {
            positions.requireActive(organizationId, employee.getPositionId());
        }
        if (employee.getManagerId() != null) {
            boolean managerStillCurrent = employees.findByOrganizationIdAndId(organizationId, employee.getManagerId())
                    .map(m -> m.getStatus().isCurrent()).orElse(false);
            if (!managerStillCurrent) {
                employee.assignManager(null, now);
                changes.put("managerCleared", true);
            }
        }
        employee.changeStatus(target, null, now);
    }

    // ------------------------------------------------------------------ helpers

    private EmployeeProfile load(UUID organizationId, UUID employeeId) {
        return employees.findByOrganizationIdAndId(organizationId, employeeId)
                .orElseThrow(() -> new ResourceNotFoundException("employee"));
    }

    private EmployeeRef requireCurrentEmployee(UUID organizationId, UUID employeeId) {
        EmployeeProfile employee = load(organizationId, employeeId);
        if (!employee.getStatus().isCurrent()) {
            throw new ConflictException("This employee is inactive.");
        }
        return EmployeeDirectoryAdapter.ref(employee);
    }

    /** Whether {@code start} is {@code target} or reports (directly or not) to {@code target}. */
    private boolean reportsTo(UUID organizationId, UUID start, UUID target) {
        Set<UUID> seen = new HashSet<>();
        UUID current = start;
        while (current != null && seen.size() < MAX_CHAIN && seen.add(current)) {
            if (current.equals(target)) {
                return true;
            }
            current = employees.findByOrganizationIdAndId(organizationId, current)
                    .map(EmployeeProfile::getManagerId).orElse(null);
        }
        return false;
    }

    /** The membership must be in this organization (404), not removed (409) and not linked elsewhere (409). */
    private void requireLinkable(UUID organizationId, UUID membershipId, UUID employeeId) {
        memberships.requireLinkable(organizationId, membershipId);
        employees.findByOrganizationIdAndMembershipId(organizationId, membershipId).ifPresent(existing -> {
            if (!existing.getId().equals(employeeId)) {
                throw new ConflictException("This member already has an employee profile.");
            }
        });
    }

    private EmployeeView view(UUID organizationId, EmployeeProfile employee) {
        return views(organizationId, List.of(employee)).getFirst();
    }

    private List<EmployeeView> views(UUID organizationId, Collection<EmployeeProfile> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Set<UUID> membershipIds = ids(list, EmployeeProfile::getMembershipId);
        Set<UUID> departmentIds = ids(list, EmployeeProfile::getDepartmentId);
        Set<UUID> positionIds = ids(list, EmployeeProfile::getPositionId);
        Set<UUID> managerIds = ids(list, EmployeeProfile::getManagerId);
        Map<UUID, MemberLink> links = memberships.linksOf(organizationId, membershipIds);
        Map<UUID, DepartmentRef> departmentRefs = departments.findRefs(organizationId, departmentIds);
        Map<UUID, PositionRef> positionRefs = positions.findRefs(organizationId, positionIds);
        Map<UUID, EmployeeRef> managers = employees.findByOrganizationIdAndIdIn(organizationId, managerIds).stream()
                .collect(Collectors.toMap(EmployeeProfile::getId, EmployeeDirectoryAdapter::ref));
        Map<UUID, Long> reports = EmployeeDirectoryAdapter.toMap(employees
                .countReportsByOrganizationGroupedByManager(organizationId,
                        list.stream().map(EmployeeProfile::getId).toList()));
        return list.stream().map(e -> new EmployeeView(e.getId(), e.getOrganizationId(), e.getMembershipId(),
                e.getMembershipId() == null || !links.containsKey(e.getMembershipId()) ? null
                        : links.get(e.getMembershipId()).status(),
                e.getEmployeeNumber(), e.getDisplayName(), e.getProfessionalEmail(),
                e.getPositionId() == null ? null : positionRefs.get(e.getPositionId()),
                e.getDepartmentId() == null ? null : departmentRefs.get(e.getDepartmentId()),
                e.getManagerId() == null ? null : managers.get(e.getManagerId()), e.getStatus(), e.getStartDate(),
                e.getEndDate(), e.getDescription(), reports.getOrDefault(e.getId(), 0L), e.getCreatedAt(),
                e.getUpdatedAt(), e.getVersion())).toList();
    }

    private static Set<UUID> ids(Collection<EmployeeProfile> list, java.util.function.Function<EmployeeProfile, UUID> f) {
        return list.stream().map(f).filter(Objects::nonNull).collect(Collectors.toSet());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String normalizeEmail(String email) {
        String value = blankToNull(email);
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    private static Map<String, Object> details(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            Object value = keyValues[i + 1];
            if (value != null) {
                map.put((String) keyValues[i], value instanceof UUID id ? id.toString() : value);
            }
        }
        return map;
    }
}
