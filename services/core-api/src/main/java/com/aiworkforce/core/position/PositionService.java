package com.aiworkforce.core.position;

import com.aiworkforce.core.access.Permission;
import com.aiworkforce.core.access.TenantAccessService;
import com.aiworkforce.core.audit.AuditLog;
import com.aiworkforce.core.common.exception.ConflictException;
import com.aiworkforce.core.common.exception.ResourceNotFoundException;
import com.aiworkforce.core.common.persistence.LikePatterns;
import com.aiworkforce.core.department.DepartmentDirectory;
import com.aiworkforce.core.department.DepartmentRef;
import com.aiworkforce.core.identity.AuthenticatedUser;
import com.aiworkforce.core.workforce.ArchiveStatus;
import com.aiworkforce.core.workforce.EmployeeDirectory;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Professional positions of an organization. A position never carries authorization. */
@Service
public class PositionService {

    static final int MAX_POSITIONS = 500;

    private final PositionRepository positions;
    private final TenantAccessService access;
    private final DepartmentDirectory departments;
    private final EmployeeDirectory employees;
    private final AuditLog audit;
    private final Clock clock;

    public PositionService(PositionRepository positions, TenantAccessService access, DepartmentDirectory departments,
            EmployeeDirectory employees, AuditLog audit, Clock clock) {
        this.positions = positions;
        this.access = access;
        this.departments = departments;
        this.employees = employees;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Page<PositionView> list(UUID organizationId, AuthenticatedUser user, String query, ArchiveStatus status,
            UUID departmentId, Pageable pageable) {
        access.require(organizationId, user, Permission.POSITION_READ);
        Page<Position> page = positions.searchByOrganization(organizationId, status, departmentId,
                LikePatterns.contains(query), pageable);
        Map<UUID, PositionView> views = views(organizationId, page.getContent()).stream()
                .collect(Collectors.toMap(PositionView::id, v -> v));
        return page.map(p -> views.get(p.getId()));
    }

    @Transactional(readOnly = true)
    public PositionView get(UUID organizationId, UUID positionId, AuthenticatedUser user) {
        access.require(organizationId, user, Permission.POSITION_READ);
        return views(organizationId, List.of(load(organizationId, positionId))).getFirst();
    }

    @Transactional
    public PositionView create(UUID organizationId, AuthenticatedUser user, String title, String code,
            String description, UUID departmentId) {
        access.require(organizationId, user, Permission.POSITION_MANAGE);
        if (positions.countByOrganizationId(organizationId) >= MAX_POSITIONS) {
            throw new ConflictException("An organization can define at most " + MAX_POSITIONS + " positions.");
        }
        String normalizedCode = normalizeCode(code);
        requireUnique(organizationId, title.trim(), normalizedCode, null);
        if (departmentId != null) {
            departments.requireActive(organizationId, departmentId);
        }
        Position position = positions.save(new Position(organizationId, title.trim(), normalizedCode,
                clean(description), departmentId, Instant.now(clock)));
        positions.flush();
        audit.record(organizationId, user.userId(), "position.created", "position", position.getId(),
                details("title", position.getTitle(), "code", normalizedCode, "departmentId", departmentId));
        return views(organizationId, List.of(position)).getFirst();
    }

    @Transactional
    public PositionView update(UUID organizationId, UUID positionId, AuthenticatedUser user, String title,
            String code, String description, Long expectedVersion) {
        access.require(organizationId, user, Permission.POSITION_MANAGE);
        Position position = load(organizationId, positionId);
        requireVersion(position, expectedVersion);
        String nextTitle = title == null ? position.getTitle() : title.trim();
        String nextCode = code == null ? position.getCode() : normalizeCode(code);
        requireUnique(organizationId, nextTitle, nextCode, positionId);
        position.update(nextTitle, nextCode, description == null ? position.getDescription() : clean(description),
                Instant.now(clock));
        positions.flush();
        audit.record(organizationId, user.userId(), "position.updated", "position", positionId,
                details("title", nextTitle, "code", nextCode));
        return views(organizationId, List.of(position)).getFirst();
    }

    /** Associates the position with a department (or removes the association with {@code null}). */
    @Transactional
    public PositionView changeDepartment(UUID organizationId, UUID positionId, AuthenticatedUser user,
            UUID departmentId) {
        access.require(organizationId, user, Permission.POSITION_MANAGE);
        Position position = load(organizationId, positionId);
        if (departmentId != null) {
            departments.requireActive(organizationId, departmentId);
        }
        position.assignDepartment(departmentId, Instant.now(clock));
        positions.flush();
        audit.record(organizationId, user.userId(), "position.department.changed", "position", positionId,
                details("departmentId", departmentId));
        return views(organizationId, List.of(position)).getFirst();
    }

    @Transactional
    public PositionView archive(UUID organizationId, UUID positionId, AuthenticatedUser user) {
        access.require(organizationId, user, Permission.POSITION_MANAGE);
        Position position = load(organizationId, positionId);
        if (position.getStatus() == ArchiveStatus.ARCHIVED) {
            throw new ConflictException("This position is already archived.");
        }
        Long current = employees.currentCountByPosition(organizationId).get(positionId);
        if (current != null && current > 0) {
            throw new ConflictException("This position is still held by employees. Reassign them first.");
        }
        position.archive(Instant.now(clock));
        positions.flush();
        audit.record(organizationId, user.userId(), "position.archived", "position", positionId,
                details("title", position.getTitle()));
        return views(organizationId, List.of(position)).getFirst();
    }

    @Transactional
    public PositionView restore(UUID organizationId, UUID positionId, AuthenticatedUser user) {
        access.require(organizationId, user, Permission.POSITION_MANAGE);
        Position position = load(organizationId, positionId);
        if (position.getStatus() == ArchiveStatus.ACTIVE) {
            throw new ConflictException("This position is not archived.");
        }
        if (position.getDepartmentId() != null) {
            departments.requireActive(organizationId, position.getDepartmentId());
        }
        position.restore(Instant.now(clock));
        positions.flush();
        audit.record(organizationId, user.userId(), "position.restored", "position", positionId,
                details("title", position.getTitle()));
        return views(organizationId, List.of(position)).getFirst();
    }

    /** Deletes a position that was never held. Otherwise archive it. */
    @Transactional
    public void delete(UUID organizationId, UUID positionId, AuthenticatedUser user) {
        access.require(organizationId, user, Permission.POSITION_MANAGE);
        Position position = load(organizationId, positionId);
        if (employees.totalCountInPosition(organizationId, positionId) > 0) {
            throw new ConflictException("Employees hold or held this position. Archive it instead.");
        }
        try {
            positions.delete(position);
            positions.flush();
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("This position is still referenced. Archive it instead.");
        }
        audit.record(organizationId, user.userId(), "position.deleted", "position", positionId,
                details("title", position.getTitle(), "code", position.getCode()));
    }

    // ------------------------------------------------------------------ helpers

    private Position load(UUID organizationId, UUID positionId) {
        return positions.findByOrganizationIdAndId(organizationId, positionId)
                .orElseThrow(() -> new ResourceNotFoundException("position"));
    }

    private void requireUnique(UUID organizationId, String title, String code, UUID excludedId) {
        boolean codeTaken = excludedId == null
                ? positions.existsByOrganizationIdAndCode(organizationId, code)
                : positions.existsByOrganizationIdAndCodeAndIdNot(organizationId, code, excludedId);
        if (codeTaken) {
            throw new ConflictException("A position with this code already exists.");
        }
        boolean titleTaken = excludedId == null
                ? positions.existsByOrganizationIdAndTitleIgnoreCase(organizationId, title)
                : positions.existsByOrganizationIdAndTitleIgnoreCaseAndIdNot(organizationId, title, excludedId);
        if (titleTaken) {
            throw new ConflictException("A position with this title already exists.");
        }
    }

    private static void requireVersion(Position position, Long expectedVersion) {
        if (expectedVersion != null && expectedVersion != position.getVersion()) {
            throw new ConflictException("This position was modified by someone else. Reload and retry.");
        }
    }

    private List<PositionView> views(UUID organizationId, Collection<Position> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Set<UUID> departmentIds = list.stream().map(Position::getDepartmentId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, DepartmentRef> refs = departments.findRefs(organizationId, departmentIds);
        Map<UUID, Long> counts = employees.currentCountByPosition(organizationId);
        return list.stream().map(p -> new PositionView(p.getId(), p.getOrganizationId(), p.getTitle(), p.getCode(),
                p.getDescription(), p.getDepartmentId() == null ? null : refs.get(p.getDepartmentId()), p.getStatus(),
                counts.getOrDefault(p.getId(), 0L), p.getCreatedAt(), p.getUpdatedAt(), p.getVersion())).toList();
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
}
