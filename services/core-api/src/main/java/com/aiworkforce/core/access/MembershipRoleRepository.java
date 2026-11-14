package com.aiworkforce.core.access;

import com.aiworkforce.core.common.persistence.TenantScoped;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Role assignments, always queried within one organization. */
@TenantScoped
interface MembershipRoleRepository extends JpaRepository<MembershipRole, MembershipRole.Key> {

    List<MembershipRole> findByOrganizationIdAndIdMembershipId(UUID organizationId, UUID membershipId);

    List<MembershipRole> findByOrganizationIdAndIdMembershipIdIn(UUID organizationId, Collection<UUID> membershipIds);

    boolean existsByOrganizationIdAndIdRoleId(UUID organizationId, UUID roleId);

    /** Effective permissions of one membership: union of the permissions of its roles. */
    @Query("""
            select distinct rp.id.permission from MembershipRole mr, RolePermission rp
            where mr.organizationId = :organizationId and mr.id.membershipId = :membershipId
              and rp.organizationId = :organizationId and rp.id.roleId = mr.id.roleId""")
    Set<Permission> permissionsOfMembershipInOrganization(
            @Param("organizationId") UUID organizationId, @Param("membershipId") UUID membershipId);

    /** Number of ACTIVE memberships holding the built-in OWNER role. */
    @Query("""
            select count(distinct m.id) from Membership m, MembershipRole mr, Role r
            where m.organizationId = :organizationId and m.status = com.aiworkforce.core.access.Membership.Status.ACTIVE
              and mr.organizationId = :organizationId and mr.id.membershipId = m.id
              and r.organizationId = :organizationId and r.id = mr.id.roleId
              and r.code = com.aiworkforce.core.access.BuiltInRole.OWNER""")
    long countActiveOwnersInOrganization(@Param("organizationId") UUID organizationId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from MembershipRole mr where mr.organizationId = :organizationId and mr.id.membershipId = :membershipId")
    void deleteByOrganizationIdAndMembershipId(
            @Param("organizationId") UUID organizationId, @Param("membershipId") UUID membershipId);
}
