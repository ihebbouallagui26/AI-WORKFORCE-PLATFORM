package com.aiworkforce.core.provider;

import com.aiworkforce.core.common.persistence.TenantScoped;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Encrypted secrets, always addressed within one organization. */
@TenantScoped
interface SecretEntryRepository extends JpaRepository<SecretEntry, UUID> {

    Optional<SecretEntry> findByOrganizationIdAndId(UUID organizationId, UUID id);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from SecretEntry s where s.organizationId = :organizationId and s.id = :id")
    void deleteByOrganizationIdAndId(@Param("organizationId") UUID organizationId, @Param("id") UUID id);
}
