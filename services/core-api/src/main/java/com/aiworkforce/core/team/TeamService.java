package com.aiworkforce.core.team;

import com.aiworkforce.core.access.OrganizationLock;
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
import com.aiworkforce.core.workforce.EmployeeDirectory.EmployeeRef;
import com.aiworkforce.core.workforce.EmployeeLifecycleParticipant;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Teams and their human members. A team may mix departments but never organizations: every
 * employee and department is resolved inside the organization of the request. The leader must be
 * a current member of the team, and only current (not INACTIVE) employees can join.
 */
@Service
public class TeamService implements EmployeeLifecycleParticipant {

    static final int MAX_TEAMS = 500;
    static final int MAX_MEMBERS = 500;

    private final TeamRepository teams;
    private final TeamMemberRepository members;
    private final TenantAccessService access;
    private final OrganizationLock lock;
    private final DepartmentDirectory departments;
    private final EmployeeDirectory employees;
    private final AuditLog audit;
    private final Clock clock;

    public TeamService(TeamRepository teams, TeamMemberRepository members, TenantAccessService access,
            OrganizationLock lock, DepartmentDirectory departments, EmployeeDirectory employees, AuditLog audit,
            Clock clock) {
        this.teams = teams;
        this.members = members;
        this.access = access;
        this.lock = lock;
        this.departments = departments;
        this.employees = employees;
        this.audit = audit;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ queries

    @Transactional(readOnly = true)
    public Page<TeamView> list(UUID organizationId, AuthenticatedUser user, String query, ArchiveStatus status,
            UUID departmentId, Pageable pageable) {
        access.require(organizationId, user, Permission.TEAM_READ);
        Page<Team> page = teams.searchByOrganization(organizationId, status, departmentId,
                LikePatterns.contains(query), pageable);
        Map<UUID, TeamView> views = views(organizationId, page.getContent()).stream()
                .collect(Collectors.toMap(TeamView::id, v -> v));
        return page.map(t -> views.get(t.getId()));
    }

    @Transactional(readOnly = true)
    public TeamView.Detail get(UUID organizationId, UUID teamId, AuthenticatedUser user) {
        access.require(organizationId, user, Permission.TEAM_READ);
        Team team = load(organizationId, teamId);
        return new TeamView.Detail(view(organizationId, team), memberViews(organizationId, team, false));
    }

    @Transactional(readOnly = true)
    public List<TeamView.Member> members(UUID organizationId, UUID teamId, AuthenticatedUser user,
            boolean includeFormer) {
        access.require(organizationId, user, Permission.TEAM_READ);
        return memberViews(organizationId, load(organizationId, teamId), includeFormer);
    }

    /** Teams an employee currently belongs to. */
    @Transactional(readOnly = true)
    public List<TeamView> teamsOfEmployee(UUID organizationId, UUID employeeId, AuthenticatedUser user) {
        access.require(organizationId, user, Permission.TEAM_READ);
        if (!employees.findRefs(organizationId, Set.of(employeeId)).containsKey(employeeId)) {
            throw new ResourceNotFoundException("employee");
        }
        Set<UUID> teamIds = members.findByOrganizationIdAndEmployeeIdAndLeftAtIsNull(organizationId, employeeId)
                .stream().map(TeamMember::getTeamId).collect(Collectors.toSet());
        return views(organizationId, teams.findByOrganizationIdAndIdIn(organizationId, teamIds));
    }

    // ------------------------------------------------------------------ commands

    @Transactional
    public TeamView create(UUID organizationId, AuthenticatedUser user, String name, String description,
            UUID departmentId, UUID leaderEmployeeId) {
        access.require(organizationId, user, Permission.TEAM_MANAGE);
        lock.acquire(organizationId);
        if (teams.countByOrganizationId(organizationId) >= MAX_TEAMS) {
            throw new ConflictException("An organization can define at most " + MAX_TEAMS + " teams.");
        }
        if (teams.existsByOrganizationIdAndNameIgnoreCase(organizationId, name.trim())) {
            throw new ConflictException("A team with this name already exists.");
        }
        if (departmentId != null) {
            departments.requireActive(organizationId, departmentId);
        }
        Instant now = Instant.now(clock);
        Team team = teams.save(new Team(organizationId, name.trim(), clean(description), departmentId, now));
        if (leaderEmployeeId != null) {
            employees.requireCurrent(organizationId, leaderEmployeeId);
            members.save(new TeamMember(organizationId, team.getId(), leaderEmployeeId, user.userId(), now));
            team.assignLeader(leaderEmployeeId, now);
        }
        teams.flush();
        audit.record(organizationId, user.userId(), "team.created", "team", team.getId(),
                details("name", team.getName(), "departmentId", departmentId, "leaderEmployeeId", leaderEmployeeId));
        return view(organizationId, team);
    }

    @Transactional
    public TeamView update(UUID organizationId, UUID teamId, AuthenticatedUser user, String name, String description,
            Long expectedVersion) {
        access.require(organizationId, user, Permission.TEAM_MANAGE);
        Team team = load(organizationId, teamId);
        if (expectedVersion != null && expectedVersion != team.getVersion()) {
            throw new ConflictException("This team was modified by someone else. Reload and retry.");
        }
        String nextName = name == null ? team.getName() : name.trim();
        if (!nextName.equalsIgnoreCase(team.getName())
                && teams.existsByOrganizationIdAndNameIgnoreCaseAndIdNot(organizationId, nextName, teamId)) {
            throw new ConflictException("A team with this name already exists.");
        }
        team.update(nextName, description == null ? team.getDescription() : clean(description), Instant.now(clock));
        teams.flush();
        audit.record(organizationId, user.userId(), "team.updated", "team", teamId, details("name", nextName));
        return view(organizationId, team);
    }

    @Transactional
    public TeamView changeDepartment(UUID organizationId, UUID teamId, AuthenticatedUser user, UUID departmentId) {
        access.require(organizationId, user, Permission.TEAM_MANAGE);
        Team team = load(organizationId, teamId);
        if (departmentId != null) {
            departments.requireActive(organizationId, departmentId);
        }
        team.assignDepartment(departmentId, Instant.now(clock));
        teams.flush();
        audit.record(organizationId, user.userId(), "team.department.changed", "team", teamId,
                details("departmentId", departmentId));
        return view(organizationId, team);
    }

    /** Sets the team leader, who must already be a current member of the team (or clears it with null). */
    @Transactional
    public TeamView assignLeader(UUID organizationId, UUID teamId, AuthenticatedUser user, UUID employeeId) {
        access.require(organizationId, user, Permission.TEAM_MANAGE);
        lock.acquire(organizationId);
        Team team = load(organizationId, teamId);
        if (employeeId != null) {
            requireActiveTeam(team);
            employees.requireCurrent(organizationId, employeeId);
            if (!members.existsByOrganizationIdAndTeamIdAndEmployeeIdAndLeftAtIsNull(organizationId, teamId,
                    employeeId)) {
                throw new ConflictException("The team leader must be an active member of the team.");
            }
        }
        team.assignLeader(employeeId, Instant.now(clock));
        teams.flush();
        audit.record(organizationId, user.userId(), "team.leader.assigned", "team", teamId,
                details("employeeId", employeeId));
        return view(organizationId, team);
    }

    @Transactional
    public TeamView.Member addMember(UUID organizationId, UUID teamId, AuthenticatedUser user, UUID employeeId) {
        access.require(organizationId, user, Permission.TEAM_MANAGE);
        lock.acquire(organizationId);
        Team team = load(organizationId, teamId);
        requireActiveTeam(team);
        EmployeeRef employee = employees.requireCurrent(organizationId, employeeId);
        if (members.existsByOrganizationIdAndTeamIdAndEmployeeIdAndLeftAtIsNull(organizationId, teamId, employeeId)) {
            throw new ConflictException("This employee is already a member of the team.");
        }
        long current = members.findByOrganizationIdAndTeamIdAndLeftAtIsNullOrderByJoinedAtAsc(organizationId, teamId)
                .size();
        if (current >= MAX_MEMBERS) {
            throw new ConflictException("A team can have at most " + MAX_MEMBERS + " members.");
        }
        TeamMember member = members.save(new TeamMember(organizationId, teamId, employeeId, user.userId(),
                Instant.now(clock)));
        members.flush();
        audit.record(organizationId, user.userId(), "team.member.added", "team", teamId,
                details("employeeId", employeeId));
        return new TeamView.Member(employee, member.getJoinedAt(), null, employeeId.equals(team.getLeaderEmployeeId()));
    }

    /** The member leaves the team (history is kept). The leader must be replaced first. */
    @Transactional
    public void removeMember(UUID organizationId, UUID teamId, AuthenticatedUser user, UUID employeeId) {
        access.require(organizationId, user, Permission.TEAM_MANAGE);
        lock.acquire(organizationId);
        Team team = load(organizationId, teamId);
        TeamMember member = members
                .findByOrganizationIdAndTeamIdAndEmployeeIdAndLeftAtIsNull(organizationId, teamId, employeeId)
                .orElseThrow(() -> new ResourceNotFoundException("team member"));
        if (employeeId.equals(team.getLeaderEmployeeId())) {
            throw new ConflictException("This employee leads the team. Assign another leader first.");
        }
        member.leave(Instant.now(clock));
        members.flush();
        audit.record(organizationId, user.userId(), "team.member.removed", "team", teamId,
                details("employeeId", employeeId));
    }

    @Transactional
    public TeamView archive(UUID organizationId, UUID teamId, AuthenticatedUser user) {
        access.require(organizationId, user, Permission.TEAM_MANAGE);
        Team team = load(organizationId, teamId);
        if (team.getStatus() == ArchiveStatus.ARCHIVED) {
            throw new ConflictException("This team is already archived.");
        }
        team.archive(Instant.now(clock));
        teams.flush();
        audit.record(organizationId, user.userId(), "team.archived", "team", teamId, details("name", team.getName()));
        return view(organizationId, team);
    }

    @Transactional
    public TeamView restore(UUID organizationId, UUID teamId, AuthenticatedUser user) {
        access.require(organizationId, user, Permission.TEAM_MANAGE);
        Team team = load(organizationId, teamId);
        if (team.getStatus() == ArchiveStatus.ACTIVE) {
            throw new ConflictException("This team is not archived.");
        }
        team.restore(Instant.now(clock));
        teams.flush();
        audit.record(organizationId, user.userId(), "team.restored", "team", teamId, details("name", team.getName()));
        return view(organizationId, team);
    }

    // ------------------------------------------------------------------ employee lifecycle

    @Override
    @Transactional(readOnly = true)
    public void beforeDeactivation(UUID organizationId, UUID employeeId) {
        if (teams.existsByOrganizationIdAndLeaderEmployeeIdAndStatus(organizationId, employeeId,
                ArchiveStatus.ACTIVE)) {
            throw new ConflictException("This employee leads a team. Assign another leader first.");
        }
    }

    /** The deactivated employee leaves every team (history kept); archived teams lose them as leader. */
    @Override
    @Transactional
    public void afterDeactivation(UUID organizationId, UUID employeeId) {
        Instant now = Instant.now(clock);
        members.findByOrganizationIdAndEmployeeIdAndLeftAtIsNull(organizationId, employeeId)
                .forEach(member -> member.leave(now));
        teams.findByOrganizationIdAndLeaderEmployeeId(organizationId, employeeId)
                .forEach(team -> team.assignLeader(null, now));
    }

    // ------------------------------------------------------------------ helpers

    private Team load(UUID organizationId, UUID teamId) {
        return teams.findByOrganizationIdAndId(organizationId, teamId)
                .orElseThrow(() -> new ResourceNotFoundException("team"));
    }

    private static void requireActiveTeam(Team team) {
        if (team.getStatus() == ArchiveStatus.ARCHIVED) {
            throw new ConflictException("This team is archived.");
        }
    }

    private TeamView view(UUID organizationId, Team team) {
        return views(organizationId, List.of(team)).getFirst();
    }

    private List<TeamView> views(UUID organizationId, Collection<Team> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Map<UUID, DepartmentRef> departmentRefs = departments.findRefs(organizationId,
                list.stream().map(Team::getDepartmentId).filter(Objects::nonNull).collect(Collectors.toSet()));
        Map<UUID, EmployeeRef> leaders = employees.findRefs(organizationId,
                list.stream().map(Team::getLeaderEmployeeId).filter(Objects::nonNull).collect(Collectors.toSet()));
        Map<UUID, Long> counts = new HashMap<>();
        for (Object[] row : members.countCurrentByOrganizationGroupedByTeam(organizationId,
                list.stream().map(Team::getId).toList())) {
            counts.put((UUID) row[0], (Long) row[1]);
        }
        return list.stream().map(t -> new TeamView(t.getId(), t.getOrganizationId(), t.getName(), t.getDescription(),
                t.getDepartmentId() == null ? null : departmentRefs.get(t.getDepartmentId()),
                t.getLeaderEmployeeId() == null ? null : leaders.get(t.getLeaderEmployeeId()), t.getStatus(),
                counts.getOrDefault(t.getId(), 0L), t.getCreatedAt(), t.getUpdatedAt(), t.getVersion())).toList();
    }

    private List<TeamView.Member> memberViews(UUID organizationId, Team team, boolean includeFormer) {
        List<TeamMember> rows = includeFormer
                ? members.findByOrganizationIdAndTeamIdOrderByJoinedAtDesc(organizationId, team.getId())
                : members.findByOrganizationIdAndTeamIdAndLeftAtIsNullOrderByJoinedAtAsc(organizationId, team.getId());
        Map<UUID, EmployeeRef> refs = employees.findRefs(organizationId,
                rows.stream().map(TeamMember::getEmployeeId).collect(Collectors.toSet()));
        return rows.stream().map(m -> new TeamView.Member(refs.get(m.getEmployeeId()), m.getJoinedAt(),
                m.getLeftAt(), m.getEmployeeId().equals(team.getLeaderEmployeeId()) && m.isCurrent())).toList();
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
