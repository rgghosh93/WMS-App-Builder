package com.wms.masterdata.item;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.wms.common.ActiveStatus;
import com.wms.common.measure.DimensionUom;
import com.wms.common.measure.WeightUom;

/** An item with its packaging levels, smallest first. */
public record ItemResponse(
        UUID id,
        String itemId,
        String description,
        ActiveStatus status,
        String barcode,
        boolean lotControlled,
        boolean serialControlled,
        boolean expiryControlled,
        Integer shelfLifeDays,
        BigDecimal width,
        BigDecimal depth,
        BigDecimal height,
        DimensionUom dimensionUom,
        BigDecimal weight,
        WeightUom weightUom,
        List<ItemUomResponse> uoms,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    static ItemResponse from(Item i) {
        return new ItemResponse(
                i.getId(), i.getItemId(), i.getDescription(), i.getStatus(), i.getBarcode(),
                i.isLotControlled(), i.isSerialControlled(), i.isExpiryControlled(), i.getShelfLifeDays(),
                i.getWidth(), i.getDepth(), i.getHeight(), i.getDimensionUom(),
                i.getWeight(), i.getWeightUom(),
                i.getUoms().stream().map(ItemUomResponse::from).toList(),
                i.getCreatedAt(), i.getUpdatedAt(), i.getVersion());
    }
}
