package com.aiworkforce.core.position;

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
 * Position lookups for the employee module. No permission check: the caller has already verified
 * access to the organization. Always organization-scoped.
 */
@Service
public class PositionDirectory {

    private final PositionRepository positions;

    public PositionDirectory(PositionRepository positions) {
        this.positions = positions;
    }

    @Transactional(readOnly = true)
    public Map<UUID, PositionRef> findRefs(UUID organizationId, Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return positions.findByOrganizationIdAndIdIn(organizationId, ids).stream()
                .map(PositionRef::of)
                .collect(Collectors.toMap(PositionRef::id, Function.identity()));
    }

    /** A position of this organization that is not archived: 404 if unknown here, 409 if archived. */
    @Transactional(readOnly = true)
    public PositionRef requireActive(UUID organizationId, UUID positionId) {
        Position position = positions.findByOrganizationIdAndId(organizationId, positionId)
                .orElseThrow(() -> new ResourceNotFoundException("position"));
        if (position.getStatus() == ArchiveStatus.ARCHIVED) {
            throw new ConflictException("This position is archived.");
        }
        return PositionRef.of(position);
    }
}
