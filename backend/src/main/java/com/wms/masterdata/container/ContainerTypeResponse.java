package com.wms.masterdata.container;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.wms.common.ActiveStatus;
import com.wms.common.measure.DimensionUom;
import com.wms.common.measure.VolumeUom;
import com.wms.common.measure.WeightUom;

public record ContainerTypeResponse(
        UUID id,
        String containerTypeId,
        String description,
        ContainerCategory category,
        ActiveStatus status,
        boolean reusable,
        BigDecimal width,
        BigDecimal depth,
        BigDecimal height,
        DimensionUom dimensionUom,
        BigDecimal tareWeight,
        WeightUom tareWeightUom,
        BigDecimal maxWeight,
        WeightUom maxWeightUom,
        BigDecimal maxVolume,
        VolumeUom maxVolumeUom,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    static ContainerTypeResponse from(ContainerType c) {
        return new ContainerTypeResponse(
                c.getId(), c.getContainerTypeId(), c.getDescription(), c.getCategory(), c.getStatus(), c.isReusable(),
                c.getWidth(), c.getDepth(), c.getHeight(), c.getDimensionUom(),
                c.getTareWeight(), c.getTareWeightUom(),
                c.getMaxWeight(), c.getMaxWeightUom(),
                c.getMaxVolume(), c.getMaxVolumeUom(),
                c.getCreatedAt(), c.getUpdatedAt(), c.getVersion());
    }
}
