package com.aiworkforce.core.access;

import org.springframework.data.jpa.repository.JpaRepository;

/** Global, read-only permission catalog (not organization data). */
interface PermissionDefinitionRepository extends JpaRepository<PermissionDefinition, String> {
}
