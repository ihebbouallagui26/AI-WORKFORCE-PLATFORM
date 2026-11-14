package com.aiworkforce.core.employee;

import com.aiworkforce.core.common.exception.ConflictException;
import com.aiworkforce.core.common.exception.ResourceNotFoundException;
import com.aiworkforce.core.workforce.EmployeeDirectory;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Adapter implementing the {@link EmployeeDirectory} port for the other workforce modules (no permission check). */
@Service
class EmployeeDirectoryAdapter implements EmployeeDirectory {

    private final EmployeeRepository employees;

    EmployeeDirectoryAdapter(EmployeeRepository employees) {
        this.employees = employees;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, EmployeeRef> findRefs(UUID organizationId, Collection<UUID> employeeIds) {
        if (employeeIds.isEmpty()) {
            return Map.of();
        }
        return employees.findByOrganizationIdAndIdIn(organizationId, employeeIds).stream()
                .map(EmployeeDirectoryAdapter::ref)
                .collect(Collectors.toMap(EmployeeRef::id, Function.identity()));
    }

    @Override
    @Transactional(readOnly = true)
    public EmployeeRef requireCurrent(UUID organizationId, UUID employeeId) {
        EmployeeProfile employee = employees.findByOrganizationIdAndId(organizationId, employeeId)
                .orElseThrow(() -> new ResourceNotFoundException("employee"));
        if (!employee.getStatus().isCurrent()) {
            throw new ConflictException("This employee is inactive.");
        }
        return ref(employee);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Long> currentCountByDepartment(UUID organizationId) {
        return toMap(employees.countCurrentByOrganizationGroupedByDepartment(organizationId));
    }

    @Override
    @Transactional(readOnly = true)
    public long currentCountInDepartment(UUID organizationId, UUID departmentId) {
        return currentCountByDepartment(organizationId).getOrDefault(departmentId, 0L);
    }

    @Override
    @Transactional(readOnly = true)
    public long totalCountInDepartment(UUID organizationId, UUID departmentId) {
        return employees.countByOrganizationIdAndDepartmentId(organizationId, departmentId);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Long> currentCountByPosition(UUID organizationId) {
        return toMap(employees.countCurrentByOrganizationGroupedByPosition(organizationId));
    }

    @Override
    @Transactional(readOnly = true)
    public long totalCountInPosition(UUID organizationId, UUID positionId) {
        return employees.countByOrganizationIdAndPositionId(organizationId, positionId);
    }

    static EmployeeRef ref(EmployeeProfile e) {
        return new EmployeeRef(e.getId(), e.getDisplayName(), e.getStatus());
    }

    static Map<UUID, Long> toMap(List<Object[]> rows) {
        Map<UUID, Long> map = new HashMap<>();
        for (Object[] row : rows) {
            map.put((UUID) row[0], (Long) row[1]);
        }
        return map;
    }
}
