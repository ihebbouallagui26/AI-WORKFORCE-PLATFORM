package com.aiworkforce.core.position;

import com.aiworkforce.core.workforce.ArchiveStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/** Short reference to a position for other modules and views. */
@Schema(name = "PositionReference")
public record PositionRef(UUID id, String title, String code, ArchiveStatus status) {

    static PositionRef of(Position p) {
        return new PositionRef(p.getId(), p.getTitle(), p.getCode(), p.getStatus());
    }
}
