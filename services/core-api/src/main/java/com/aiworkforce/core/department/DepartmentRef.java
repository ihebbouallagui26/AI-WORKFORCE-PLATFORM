package com.aiworkforce.core.department;

import com.aiworkforce.core.workforce.ArchiveStatus;
import java.util.UUID;

/** Short reference to a department for other modules and views. */
@io.swagger.v3.oas.annotations.media.Schema(name = "DepartmentReference")
public record DepartmentRef(UUID id, String name, String code, ArchiveStatus status) {

    static DepartmentRef of(Department d) {
        return new DepartmentRef(d.getId(), d.getName(), d.getCode(), d.getStatus());
    }
}
