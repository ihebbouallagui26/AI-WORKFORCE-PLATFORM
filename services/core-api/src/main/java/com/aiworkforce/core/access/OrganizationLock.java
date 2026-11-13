package com.aiworkforce.core.access;

import com.aiworkforce.core.common.exception.ResourceNotFoundException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Serialises structural changes of one organization (row lock held until the end of the
 * transaction). Used wherever an invariant spans several rows and cannot be a simple constraint:
 * last owner, department hierarchy without cycles, reporting lines without cycles.
 */
@Service
public class OrganizationLock {

    private final MembershipRepository memberships;

    public OrganizationLock(MembershipRepository memberships) {
        this.memberships = memberships;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void acquire(UUID organizationId) {
        memberships.lockOrganization(organizationId).orElseThrow(() -> new ResourceNotFoundException("organization"));
    }
}
