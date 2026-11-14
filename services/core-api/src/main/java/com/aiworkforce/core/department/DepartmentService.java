package com.aiworkforce.core.department;

import com.aiworkforce.core.access.OrganizationLock;
import com.aiworkforce.core.access.Permission;
import com.aiworkforce.core.access.TenantAccessService;
import com.aiworkforce.core.audit.AuditLog;
import com.aiworkforce.core.common.exception.ConflictException;
import com.aiworkforce.core.common.exception.ResourceNotFoundException;
import com.aiworkforce.core.common.persistence.LikePatterns;
import com.aiworkforce.core.identity.AuthenticatedUser;
import com.aiworkforce.core.workforce.AgentDirectory;
import com.aiworkforce.core.workforce.ArchiveStatus;
import com.aiworkforce.core.workforce.EmployeeDirectory;
import com.aiworkforce.core.workforce.EmployeeDirectory.EmployeeRef;
import com.aiworkforce.core.workforce.EmployeeLifecycleParticipant;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Departments of an organization. Structural changes (create, move, archive, delete) take the
 * organization lock, so the hierarchy can never contain a cycle even under concurrent requests.
 */
@Service
public class DepartmentService implements EmployeeLifecycleParticipant {

    static final int MAX_DEPARTMENTS = 500;
    static final int MAX_DEPTH = 10;

    private final DepartmentRepository departments;
    private final TenantAccessService access;
    private final OrganizationLock lock;
    private final EmployeeDirectory employees;
    private final AgentDirectory agents;
    private final AuditLog audit;
    private final Clock clock;

    public DepartmentService(DepartmentRepository departments, TenantAccessService access, OrganizationLock lock,
            EmployeeDirectory employees, AgentDirectory agents, AuditLog audit, Clock clock) {
        this.departments = departments;
        this.access = access;
        this.lock = lock;
        this.employees = employees;
        this.agents = agents;
        this.audit = audit;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ queries

    @Transactional(readOnly = true)
    public Page<DepartmentView> list(UUID organizationId, AuthenticatedUser user, String query, ArchiveStatus status,
            UUID parentId, boolean rootOnly, Pageable pageable) {
        access.require(organizationId, user, Permission.DEPARTMENT_READ);
        Page<Department> page = departments.searchByOrganization(organizationId, status, parentId, rootOnly,
                LikePatterns.contains(query), pageable);
        Map<UUID, DepartmentView> views = views(organizationId, page.getContent()).stream()
                .collect(Collectors.toMap(DepartmentView::id, v -> v));
        return page.map(d -> views.get(d.getId()));
    }

    @Transactional(readOnly = true)
    public DepartmentView get(UUID organizationId, UUID departmentId, AuthenticatedUser user) {
        access.require(organizationId, user, Permission.DEPARTMENT_READ);
        return views(organizationId, List.of(load(organizationId, departmentId))).getFirst();
    }

    /** The whole hierarchy (roots first). Archived departments are only included on request. */
    @Transactional(readOnly = true)
    public List<DepartmentView.Node> tree(UUID organizationId, AuthenticatedUser user, boolean includeArchived) {
        access.require(organizationId, user, Permission.DEPARTMENT_READ);
        List<Department> all = departments.findByOrganizationId(organizationId, Sort.by("name", "id")).stream()
                .filter(d -> includeArchived || d.getStatus() == ArchiveStatus.ACTIVE)
                .toList();
        Map<UUID, DepartmentView> views = views(organizationId, all).stream()
                .collect(Collectors.toMap(DepartmentView::id, v -> v));
        Map<UUID, List<Department>> byParent = all.stream().filter(d -> d.getParentId() != null)
                .collect(Collectors.groupingBy(Department::getParentId));
        Set<UUID> known = all.stream().map(Department::getId).collect(Collectors.toSet());
        List<DepartmentView.Node> roots = new ArrayList<>();
        for (Department department : all) {
            // A department whose parent is archived (and hidden) is shown at the top level.
            if (department.getParentId() == null || !known.contains(department.getParentId())) {
                roots.add(node(department, byParent, views));
            }
        }
        return roots;
    }

    // ------------------------------------------------------------------ commands

    @Transactional
    public DepartmentView create(UUID organizationId, AuthenticatedUser user, String name, String code,
            String description, UUID parentId, UUID headEmployeeId) {
        access.require(organizationId, user, Permission.DEPARTMENT_MANAGE);
        lock.acquire(organizationId);
        if (departments.countByOrganizationId(organizationId) >= MAX_DEPARTMENTS) {
            throw new ConflictException("An organization can define at most " + MAX_DEPARTMENTS + " departments.");
        }
        String normalizedCode = normalizeCode(code);
        if (departments.existsByOrganizationIdAndCode(organizationId, normalizedCode)) {
            throw new ConflictException("A department with this code already exists.");
        }
        if (parentId != null) {
            Department parent = loadActive(organizationId, parentId);
            if (index(organizationId).depthOf(parent.getId()) + 1 > MAX_DEPTH) {
                throw new ConflictException("Departments can be nested at most " + MAX_DEPTH + " levels deep.");
            }
        }
        Department department = departments.save(new Department(organizationId, name.trim(), normalizedCode,
                clean(description), parentId, Instant.now(clock)));
        if (headEmployeeId != null) {
            employees.requireCurrent(organizationId, headEmployeeId);
            department.assignHead(headEmployeeId, Instant.now(clock));
        }
        departments.flush();
        audit.record(organizationId, user.userId(), "department.created", "department", department.getId(),
                details("name", department.getName(), "code", normalizedCode, "parentId", parentId));
        return views(organizationId, List.of(department)).getFirst();
    }

    @Transactional
    public DepartmentView update(UUID organizationId, UUID departmentId, AuthenticatedUser user, String name,
            String code, String description, Long expectedVersion) {
        access.require(organizationId, user, Permission.DEPARTMENT_MANAGE);
        Department department = load(organizationId, departmentId);
        requireVersion(department, expectedVersion);
        String nextCode = code == null ? department.getCode() : normalizeCode(code);
        if (!nextCode.equals(department.getCode())
                && departments.existsByOrganizationIdAndCodeAndIdNot(organizationId, nextCode, departmentId)) {
            throw new ConflictException("A department with this code already exists.");
        }
        department.update(name == null ? department.getName() : name.trim(), nextCode,
                description == null ? department.getDescription() : clean(description), Instant.now(clock));
        departments.flush();
        audit.record(organizationId, user.userId(), "department.updated", "department", departmentId,
                details("name", department.getName(), "code", nextCode));
        return views(organizationId, List.of(department)).getFirst();
    }

    /** Moves a department under another one (or to the top level with {@code null}). */
    @Transactional
    public DepartmentView changeParent(UUID organizationId, UUID departmentId, AuthenticatedUser user, UUID parentId) {
        access.require(organizationId, user, Permission.DEPARTMENT_MANAGE);
        lock.acquire(organizationId);
        Department department = load(organizationId, departmentId);
        if (parentId != null) {
            if (parentId.equals(departmentId)) {
                throw new ConflictException("A department cannot be its own parent.");
            }
            Department parent = loadActive(organizationId, parentId);
            Index index = index(organizationId);
            if (index.isAncestor(departmentId, parent.getId())) {
                throw new ConflictException("A department cannot be moved under one of its own sub-departments.");
            }
            if (index.depthOf(parent.getId()) + 1 + index.heightOf(departmentId) > MAX_DEPTH) {
                throw new ConflictException("Departments can be nested at most " + MAX_DEPTH + " levels deep.");
            }
        }
        department.moveTo(parentId, Instant.now(clock));
        departments.flush();
        audit.record(organizationId, user.userId(), "department.parent.changed", "department", departmentId,
                details("parentId", parentId));
        return views(organizationId, List.of(department)).getFirst();
    }

    @Transactional
    public DepartmentView assignHead(UUID organizationId, UUID departmentId, AuthenticatedUser user,
            UUID employeeId) {
        access.require(organizationId, user, Permission.DEPARTMENT_MANAGE);
        Department department = load(organizationId, departmentId);
        if (employeeId != null) {
            if (department.getStatus() == ArchiveStatus.ARCHIVED) {
                throw new ConflictException("An archived department cannot get a new head.");
            }
            employees.requireCurrent(organizationId, employeeId);
        }
        department.assignHead(employeeId, Instant.now(clock));
        departments.flush();
        audit.record(organizationId, user.userId(), "department.head.assigned", "department", departmentId,
                details("employeeId", employeeId));
        return views(organizationId, List.of(department)).getFirst();
    }

    @Transactional
    public DepartmentView archive(UUID organizationId, UUID departmentId, AuthenticatedUser user) {
        access.require(organizationId, user, Permission.DEPARTMENT_MANAGE);
        lock.acquire(organizationId);
        Department department = load(organizationId, departmentId);
        if (department.getStatus() == ArchiveStatus.ARCHIVED) {
            throw new ConflictException("This department is already archived.");
        }
        if (employees.currentCountInDepartment(organizationId, departmentId) > 0) {
            throw new ConflictException("This department still has employees. Reassign them first.");
        }
        if (agents.countInDepartment(organizationId, departmentId) > 0) {
            throw new ConflictException("AI agents are still assigned to this department. Reassign them first.");
        }
        if (departments.existsByOrganizationIdAndParentIdAndStatus(organizationId, departmentId, ArchiveStatus.ACTIVE)) {
            throw new ConflictException("This department still has active sub-departments. Archive or move them first.");
        }
        department.archive(Instant.now(clock));
        departments.flush();
        audit.record(organizationId, user.userId(), "department.archived", "department", departmentId,
                details("name", department.getName()));
        return views(organizationId, List.of(department)).getFirst();
    }

    @Transactional
    public DepartmentView restore(UUID organizationId, UUID departmentId, AuthenticatedUser user) {
        access.require(organizationId, user, Permission.DEPARTMENT_MANAGE);
        lock.acquire(organizationId);
        Department department = load(organizationId, departmentId);
        if (department.getStatus() == ArchiveStatus.ACTIVE) {
            throw new ConflictException("This department is not archived.");
        }
        if (department.getParentId() != null) {
            loadActive(organizationId, department.getParentId());
        }
        department.restore(Instant.now(clock));
        departments.flush();
        audit.record(organizationId, user.userId(), "department.restored", "department", departmentId,
                details("name", department.getName()));
        return views(organizationId, List.of(department)).getFirst();
    }

    /** Deletes a department that was never used. Anything with history must be archived instead. */
    @Transactional
    public void delete(UUID organizationId, UUID departmentId, AuthenticatedUser user) {
        access.require(organizationId, user, Permission.DEPARTMENT_MANAGE);
        lock.acquire(organizationId);
        Department department = load(organizationId, departmentId);
        if (departments.existsByOrganizationIdAndParentId(organizationId, departmentId)) {
            throw new ConflictException("This department has sub-departments. Delete or move them first.");
        }
        if (employees.totalCountInDepartment(organizationId, departmentId) > 0) {
            throw new ConflictException("Employees are or were assigned to this department. Archive it instead.");
        }
        try {
            departments.delete(department);
            departments.flush();
        } catch (DataIntegrityViolationException e) {
            // Positions or teams still reference it (foreign keys).
            throw new ConflictException("This department is used by positions, teams or AI agents. Archive it instead.");
        }
        audit.record(organizationId, user.userId(), "department.deleted", "department", departmentId,
                details("name", department.getName(), "code", department.getCode()));
    }

    // ------------------------------------------------------------------ employee lifecycle

    @Override
    @Transactional(readOnly = true)
    public void beforeDeactivation(UUID organizationId, UUID employeeId) {
        if (departments.existsByOrganizationIdAndHeadEmployeeIdAndStatus(organizationId, employeeId,
                ArchiveStatus.ACTIVE)) {
            throw new ConflictException("This employee heads a department. Assign another head first.");
        }
    }

    @Override
    public void afterDeactivation(UUID organizationId, UUID employeeId) {
        // Nothing to clean up: archived departments keep their former head for history.
    }

    // ------------------------------------------------------------------ helpers

    private Department load(UUID organizationId, UUID departmentId) {
        return departments.findByOrganizationIdAndId(organizationId, departmentId)
                .orElseThrow(() -> new ResourceNotFoundException("department"));
    }

    private Department loadActive(UUID organizationId, UUID departmentId) {
        Department department = load(organizationId, departmentId);
        if (department.getStatus() == ArchiveStatus.ARCHIVED) {
            throw new ConflictException("This department is archived.");
        }
        return department;
    }

    private static void requireVersion(Department department, Long expectedVersion) {
        if (expectedVersion != null && expectedVersion != department.getVersion()) {
            throw new ConflictException("This department was modified by someone else. Reload and retry.");
        }
    }

    private Index index(UUID organizationId) {
        return new Index(departments.findByOrganizationId(organizationId, Sort.unsorted()));
    }

    private DepartmentView.Node node(Department department, Map<UUID, List<Department>> byParent,
            Map<UUID, DepartmentView> views) {
        List<DepartmentView.Node> children = byParent.getOrDefault(department.getId(), List.of()).stream()
                .map(child -> node(child, byParent, views)).toList();
        return new DepartmentView.Node(views.get(department.getId()), children);
    }

    private List<DepartmentView> views(UUID organizationId, Collection<Department> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Set<UUID> headIds = list.stream().map(Department::getHeadEmployeeId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, EmployeeRef> heads = employees.findRefs(organizationId, headIds);
        Map<UUID, Long> employeeCounts = employees.currentCountByDepartment(organizationId);
        Map<UUID, Long> agentCounts = agents.countByDepartment(organizationId);
        Map<UUID, Long> childCounts = new HashMap<>();
        for (Object[] row : departments.countChildrenByOrganization(organizationId,
                list.stream().map(Department::getId).toList())) {
            childCounts.put((UUID) row[0], (Long) row[1]);
        }
        return list.stream().map(d -> new DepartmentView(d.getId(), d.getOrganizationId(), d.getName(), d.getCode(),
                d.getDescription(), d.getParentId(),
                d.getHeadEmployeeId() == null ? null : heads.get(d.getHeadEmployeeId()),
                d.getStatus(), employeeCounts.getOrDefault(d.getId(), 0L), agentCounts.getOrDefault(d.getId(), 0L), childCounts.getOrDefault(d.getId(), 0L),
                d.getCreatedAt(), d.getUpdatedAt(), d.getVersion())).toList();
    }

    static String normalizeCode(String code) {
        return code.trim().toUpperCase(Locale.ROOT);
    }

    private static String clean(String text) {
        return text == null ? "" : text.trim();
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

    /** In-memory view of the hierarchy of one organization (bounded by MAX_DEPARTMENTS). */
    private static final class Index {

        private final Map<UUID, Department> byId = new HashMap<>();
        private final Map<UUID, List<UUID>> children = new HashMap<>();

        Index(List<Department> all) {
            for (Department d : all) {
                byId.put(d.getId(), d);
                if (d.getParentId() != null) {
                    children.computeIfAbsent(d.getParentId(), k -> new ArrayList<>()).add(d.getId());
                }
            }
        }

        /** Number of levels from the root down to {@code id} (a root has depth 1). */
        int depthOf(UUID id) {
            int depth = 0;
            Set<UUID> seen = new HashSet<>();
            UUID current = id;
            while (current != null && seen.add(current)) {
                depth++;
                Department d = byId.get(current);
                current = d == null ? null : d.getParentId();
            }
            return depth;
        }

        /** Whether {@code ancestor} is {@code id} itself or one of its ancestors. */
        boolean isAncestor(UUID ancestor, UUID id) {
            Set<UUID> seen = new HashSet<>();
            UUID current = id;
            while (current != null && seen.add(current)) {
                if (current.equals(ancestor)) {
                    return true;
                }
                Department d = byId.get(current);
                current = d == null ? null : d.getParentId();
            }
            return false;
        }

        /** Number of levels below {@code id}: a leaf has height 0. */
        int heightOf(UUID id) {
            return children.getOrDefault(id, List.of()).stream().mapToInt(c -> 1 + heightOf(c)).max().orElse(0);
        }
    }
}
