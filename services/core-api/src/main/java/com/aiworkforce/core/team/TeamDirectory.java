package com.aiworkforce.core.team;

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

/** Team lookups for the agent module (no permission check; always organization-scoped). */
@Service
public class TeamDirectory {

    /** Short reference to a team. */
    @io.swagger.v3.oas.annotations.media.Schema(name = "TeamReference")
    public record TeamRef(UUID id, String name, ArchiveStatus status) {
    }

    private final TeamRepository teams;

    public TeamDirectory(TeamRepository teams) {
        this.teams = teams;
    }

    @Transactional(readOnly = true)
    public Map<UUID, TeamRef> findRefs(UUID organizationId, Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return teams.findByOrganizationIdAndIdIn(organizationId, ids).stream()
                .map(t -> new TeamRef(t.getId(), t.getName(), t.getStatus()))
                .collect(Collectors.toMap(TeamRef::id, Function.identity()));
    }

    /** A team of this organization that is not archived: 404 if unknown here, 409 if archived. */
    @Transactional(readOnly = true)
    public TeamRef requireActive(UUID organizationId, UUID teamId) {
        Team team = teams.findByOrganizationIdAndId(organizationId, teamId)
                .orElseThrow(() -> new ResourceNotFoundException("team"));
        if (team.getStatus() == ArchiveStatus.ARCHIVED) {
            throw new ConflictException("This team is archived.");
        }
        return new TeamRef(team.getId(), team.getName(), team.getStatus());
    }

    /** Whether the team exists in the organization (any status). */
    @Transactional(readOnly = true)
    public TeamRef require(UUID organizationId, UUID teamId) {
        Team team = teams.findByOrganizationIdAndId(organizationId, teamId)
                .orElseThrow(() -> new ResourceNotFoundException("team"));
        return new TeamRef(team.getId(), team.getName(), team.getStatus());
    }
}
