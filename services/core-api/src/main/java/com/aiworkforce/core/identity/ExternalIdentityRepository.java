package com.aiworkforce.core.identity;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Lookup of OpenID Connect identities by their {@code (issuer, subject)} key. */
interface ExternalIdentityRepository extends JpaRepository<ExternalIdentity, UUID> {

    Optional<ExternalIdentity> findByIssuerAndSubject(String issuer, String subject);
}
