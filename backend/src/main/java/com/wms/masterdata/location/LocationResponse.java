package com.wms.masterdata.location;

import com.wms.common.measure.DimensionUom;
import com.wms.common.measure.VolumeUom;
import com.wms.common.measure.WeightUom;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record LocationResponse(
        UUID id,
        String locationId,
        LocationType type,
        String area,
        String zone,
        String aisle,
        String bay,
        String level,
        String position,
        BigDecimal width,
        BigDecimal depth,
        BigDecimal height,
        DimensionUom dimensionUom,
        BigDecimal maxWeight,
        WeightUom maxWeightUom,
        BigDecimal maxVolume,
        VolumeUom maxVolumeUom,
        Integer maxLpns,
        Integer maxSkus,
        Integer maxUnits,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    public static LocationResponse from(Location l) {
        return new LocationResponse(
                l.getId(), l.getLocationId(), l.getType(),
                l.getArea(), l.getZone(), l.getAisle(), l.getBay(), l.getLevel(), l.getPosition(),
                l.getWidth(), l.getDepth(), l.getHeight(), l.getDimensionUom(),
                l.getMaxWeight(), l.getMaxWeightUom(), l.getMaxVolume(), l.getMaxVolumeUom(),
                l.getMaxLpns(), l.getMaxSkus(), l.getMaxUnits(),
                l.getCreatedAt(), l.getUpdatedAt(), l.getVersion());
    }
}
