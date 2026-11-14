package com.aiworkforce.core.agent;

import com.aiworkforce.core.access.Permission;
import com.aiworkforce.core.access.TenantAccessService;
import com.aiworkforce.core.agent.AgentParts.Skill;
import com.aiworkforce.core.audit.AuditLog;
import com.aiworkforce.core.common.exception.ConflictException;
import com.aiworkforce.core.common.exception.ResourceNotFoundException;
import com.aiworkforce.core.common.persistence.LikePatterns;
import com.aiworkforce.core.identity.AuthenticatedUser;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reusable professional skills of an organization (assigned to agents by {@link AgentConfigurationService}). */
@Service
public class SkillService {

    static final int MAX_SKILLS = 200;

    /** Read model of a skill. */
    public record SkillView(UUID id, String name, String category, String description, boolean active, Instant createdAt,
            Instant updatedAt, long version) {

        static SkillView of(Skill s) {
            return new SkillView(s.getId(), s.getName(), s.getCategory(), s.getDescription(), s.isActive(),
                    s.getCreatedAt(), s.getUpdatedAt(), s.getVersion());
        }
    }

    private final SkillRepository skills;
    private final AgentSkillRepository assignments;
    private final TenantAccessService access;
    private final AuditLog audit;
    private final Clock clock;

    public SkillService(SkillRepository skills, AgentSkillRepository assignments, TenantAccessService access,
            AuditLog audit, Clock clock) {
        this.skills = skills;
        this.assignments = assignments;
        this.access = access;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Page<SkillView> list(UUID organizationId, AuthenticatedUser user, String query, Boolean active,
            String category, Pageable pageable) {
        access.require(organizationId, user, Permission.SKILL_READ);
        String normalizedCategory = category == null || category.isBlank() ? null : category.trim().toLowerCase(Locale.ROOT);
        return skills.searchByOrganization(organizationId, active, normalizedCategory, LikePatterns.contains(query),
                pageable).map(SkillView::of);
    }

    @Transactional
    public SkillView create(UUID organizationId, AuthenticatedUser user, String name, String category,
            String description) {
        access.require(organizationId, user, Permission.SKILL_MANAGE);
        if (skills.countByOrganizationId(organizationId) >= MAX_SKILLS) {
            throw new ConflictException("An organization can define at most " + MAX_SKILLS + " skills.");
        }
        String cleanName = name.trim();
        if (skills.existsByOrganizationIdAndNameIgnoreCase(organizationId, cleanName)) {
            throw new ConflictException("A skill with this name already exists.");
        }
        Skill skill = skills.save(new Skill(organizationId, cleanName, clean(category), clean(description),
                Instant.now(clock)));
        skills.flush();
        audit.record(organizationId, user.userId(), "skill.created", "skill", skill.getId(), Map.of("name", cleanName));
        return SkillView.of(skill);
    }

    @Transactional
    public SkillView update(UUID organizationId, UUID skillId, AuthenticatedUser user, String name, String category,
            String description, Boolean active, Long expectedVersion) {
        access.require(organizationId, user, Permission.SKILL_MANAGE);
        Skill skill = load(organizationId, skillId);
        if (expectedVersion != null && expectedVersion != skill.getVersion()) {
            throw new ConflictException("This skill was modified by someone else. Reload and retry.");
        }
        String nextName = name == null ? skill.getName() : name.trim();
        if (!nextName.equalsIgnoreCase(skill.getName())
                && skills.existsByOrganizationIdAndNameIgnoreCaseAndIdNot(organizationId, nextName, skillId)) {
            throw new ConflictException("A skill with this name already exists.");
        }
        skill.update(nextName, category == null ? skill.getCategory() : clean(category),
                description == null ? skill.getDescription() : clean(description),
                active == null ? skill.isActive() : active, Instant.now(clock));
        skills.flush();
        audit.record(organizationId, user.userId(), "skill.updated", "skill", skillId,
                Map.of("name", nextName, "active", skill.isActive()));
        return SkillView.of(skill);
    }

    /** Deletes a skill nobody uses; otherwise deactivate it. */
    @Transactional
    public void delete(UUID organizationId, UUID skillId, AuthenticatedUser user) {
        access.require(organizationId, user, Permission.SKILL_MANAGE);
        Skill skill = load(organizationId, skillId);
        if (assignments.existsByOrganizationIdAndIdSkillId(organizationId, skillId)) {
            throw new ConflictException("This skill is assigned to agents. Deactivate it instead.");
        }
        skills.delete(skill);
        skills.flush();
        audit.record(organizationId, user.userId(), "skill.deleted", "skill", skillId, Map.of("name", skill.getName()));
    }

    private Skill load(UUID organizationId, UUID skillId) {
        return skills.findByOrganizationIdAndId(organizationId, skillId)
                .orElseThrow(() -> new ResourceNotFoundException("skill"));
    }

    private static String clean(String text) {
        return text == null ? "" : text.trim();
    }
}
