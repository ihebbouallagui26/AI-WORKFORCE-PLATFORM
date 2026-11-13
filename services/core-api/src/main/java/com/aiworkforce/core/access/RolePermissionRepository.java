package com.aiworkforce.core.access;

import com.aiworkforce.core.common.persistence.TenantScoped;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Permissions granted by roles, always queried within one organization. */
@TenantScoped
interface RolePermissionRepository extends JpaRepository<RolePermission, RolePermission.Key> {

    List<RolePermission> findByOrganizationIdAndIdRoleIdIn(UUID organizationId, Collection<UUID> roleIds);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from RolePermission rp where rp.organizationId = :organizationId and rp.id.roleId = :roleId")
    void deleteByOrganizationIdAndRoleId(@Param("organizationId") UUID organizationId, @Param("roleId") UUID roleId);
}
