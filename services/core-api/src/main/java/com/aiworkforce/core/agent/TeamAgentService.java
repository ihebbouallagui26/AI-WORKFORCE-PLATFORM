package com.aiworkforce.core.agent;

import com.aiworkforce.core.access.OrganizationAccess;
import com.aiworkforce.core.access.OrganizationLock;
import com.aiworkforce.core.access.Permission;
import com.aiworkforce.core.access.TenantAccessService;
import com.aiworkforce.core.agent.AgentParts.TeamAgentMember;
import com.aiworkforce.core.audit.AuditLog;
import com.aiworkforce.core.common.exception.ConflictException;
import com.aiworkforce.core.common.exception.ResourceNotFoundException;
import com.aiworkforce.core.identity.AuthenticatedUser;
import com.aiworkforce.core.team.TeamDirectory;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AI agents inside teams, next to the human members (a separate relationship: agents are never
 * employees). An archived agent or an archived team cannot be assigned; an agent can be in several
 * teams of its organization, at most once per team.
 */
@Service
public class TeamAgentService {

    static final int MAX_AGENTS_PER_TEAM = 200;

    /** An agent's participation in a team. */
    public record TeamAgentView(AgentView agent, Instant joinedAt, Instant leftAt) {
    }

    private final TeamAgentRepository members;
    private final AgentRepository agents;
    private final AgentService agentService;
    private final AgentSupport support;
    private final TeamDirectory teams;
    private final TenantAccessService access;
    private final OrganizationLock lock;
    private final AuditLog audit;

    public TeamAgentService(TeamAgentRepository members, AgentRepository agents, AgentService agentService,
            AgentSupport support, TeamDirectory teams, TenantAccessService access, OrganizationLock lock,
            AuditLog audit) {
        this.members = members;
        this.agents = agents;
        this.agentService = agentService;
        this.support = support;
        this.teams = teams;
        this.access = access;
        this.lock = lock;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<TeamAgentView> list(UUID organizationId, UUID teamId, AuthenticatedUser user, boolean includeFormer) {
        access.require(organizationId, user, Permission.AGENT_READ);
        teams.require(organizationId, teamId);
        List<TeamAgentMember> rows = includeFormer
                ? members.findByOrganizationIdAndTeamIdOrderByJoinedAtDesc(organizationId, teamId)
                : members.findByOrganizationIdAndTeamIdAndLeftAtIsNullOrderByJoinedAtAsc(organizationId, teamId);
        return views(organizationId, rows);
    }

    @Transactional
    public TeamAgentView add(UUID organizationId, UUID teamId, AuthenticatedUser user, UUID agentId) {
        OrganizationAccess actor = access.require(organizationId, user, Permission.TEAM_MANAGE);
        actor.require(Permission.AGENT_READ);
        lock.acquire(organizationId);
        teams.requireActive(organizationId, teamId);
        Agent agent = support.load(organizationId, agentId);
        if (agent.getStatus() == AgentStatus.ARCHIVED) {
            throw new ConflictException("An archived agent cannot be assigned to a team.");
        }
        if (members.existsByOrganizationIdAndTeamIdAndAgentIdAndLeftAtIsNull(organizationId, teamId, agentId)) {
            throw new ConflictException("This agent is already in the team.");
        }
        if (members.findByOrganizationIdAndTeamIdAndLeftAtIsNullOrderByJoinedAtAsc(organizationId, teamId).size()
                >= MAX_AGENTS_PER_TEAM) {
            throw new ConflictException("A team can have at most " + MAX_AGENTS_PER_TEAM + " AI agents.");
        }
        TeamAgentMember member = members.save(new TeamAgentMember(organizationId, teamId, agentId, user.userId(),
                support.now()));
        members.flush();
        audit.record(organizationId, user.userId(), "agent.team.joined", "agent", agentId,
                AgentSupport.details("teamId", teamId));
        return views(organizationId, List.of(member)).getFirst();
    }

    @Transactional
    public void remove(UUID organizationId, UUID teamId, AuthenticatedUser user, UUID agentId) {
        access.require(organizationId, user, Permission.TEAM_MANAGE);
        lock.acquire(organizationId);
        teams.require(organizationId, teamId);
        TeamAgentMember member = members
                .findByOrganizationIdAndTeamIdAndAgentIdAndLeftAtIsNull(organizationId, teamId, agentId)
                .orElseThrow(() -> new ResourceNotFoundException("team agent"));
        member.leave(support.now());
        members.flush();
        audit.record(organizationId, user.userId(), "agent.team.left", "agent", agentId,
                AgentSupport.details("teamId", teamId));
    }

    private List<TeamAgentView> views(UUID organizationId, List<TeamAgentMember> rows) {
        Map<UUID, AgentView> byId = agentService.summaries(organizationId,
                agents.findByOrganizationIdAndIdIn(organizationId,
                        rows.stream().map(TeamAgentMember::getAgentId).collect(Collectors.toSet())))
                .stream().collect(Collectors.toMap(AgentView::id, Function.identity()));
        return rows.stream().map(m -> new TeamAgentView(byId.get(m.getAgentId()), m.getJoinedAt(), m.getLeftAt())).toList();
    }
}
