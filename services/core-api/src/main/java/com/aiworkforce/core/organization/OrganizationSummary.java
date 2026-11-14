package com.aiworkforce.core.organization;

import java.time.Instant;
import java.util.UUID;

/** Read model of an organization. */
public record OrganizationSummary(UUID id, String name, String description, String timezone, Instant createdAt,
        Instant updatedAt) {

    static OrganizationSummary of(Organization o) {
        return new OrganizationSummary(o.getId(), o.getName(), o.getDescription(), o.getTimezone(), o.getCreatedAt(),
                o.getUpdatedAt());
    }
}
