package com.aiworkforce.core.department;

import com.aiworkforce.core.common.exception.ConflictException;
import com.aiworkforce.core.common.exception.ResourceNotFoundException;
import com.aiworkforce.core.workforce.ArchiveStatus;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Department lookups for the position, employee and team modules. No permission check: the
 * caller has already verified access to the organization. Always organization-scoped.
 */
@Service
public class DepartmentDirectory {

    private final DepartmentRepository departments;

    public DepartmentDirectory(DepartmentRepository departments) {
        this.departments = departments;
    }

    @Transactional(readOnly = true)
    public Map<UUID, DepartmentRef> findRefs(UUID organizationId, Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return departments.findByOrganizationIdAndIdIn(organizationId, ids).stream()
                .map(DepartmentRef::of)
                .collect(Collectors.toMap(DepartmentRef::id, Function.identity()));
    }

    /** A department of this organization that is not archived: 404 if unknown here, 409 if archived. */
    @Transactional(readOnly = true)
    public DepartmentRef requireActive(UUID organizationId, UUID departmentId) {
        Department department = departments.findByOrganizationIdAndId(organizationId, departmentId)
                .orElseThrow(() -> new ResourceNotFoundException("department"));
        if (department.getStatus() == ArchiveStatus.ARCHIVED) {
            throw new ConflictException("This department is archived.");
        }
        return DepartmentRef.of(department);
    }
}
