package com.wms.masterdata.uom;

import java.time.Instant;
import java.util.UUID;

import com.wms.common.ActiveStatus;

public record UomResponse(
        UUID id,
        String uomCode,
        String description,
        UomKind kind,
        ActiveStatus status,
        boolean base,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    public static UomResponse from(UnitOfMeasure u) {
        return new UomResponse(u.getId(), u.getUomCode(), u.getDescription(), u.getKind(), u.getStatus(), u.isBase(),
                u.getCreatedAt(), u.getUpdatedAt(), u.getVersion());
    }
}
