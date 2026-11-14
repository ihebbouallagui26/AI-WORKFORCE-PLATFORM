package com.aiworkforce.core.identity;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Users are global records (not organization-scoped). Only used inside the identity module. */
interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {

    List<UserAccount> findByIdIn(Collection<UUID> ids);

    List<UserAccount> findByEmailAndEmailVerifiedTrue(String normalizedEmail);
}
