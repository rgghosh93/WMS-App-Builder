package com.wms.masterdata.item;

import java.math.BigDecimal;
import java.util.UUID;

import com.wms.common.measure.DimensionUom;
import com.wms.common.measure.WeightUom;
import com.wms.masterdata.uom.UnitOfMeasure;
import com.wms.masterdata.uom.UomKind;

public record ItemUomResponse(
        UUID id,
        UomSummary uom,
        int quantity,
        String barcode,
        BigDecimal width,
        BigDecimal depth,
        BigDecimal height,
        DimensionUom dimensionUom,
        BigDecimal weight,
        WeightUom weightUom) {

    public record UomSummary(UUID id, String uomCode, String description, UomKind kind) {
    }

    static ItemUomResponse from(ItemUom p) {
        UnitOfMeasure u = p.getUom();
        return new ItemUomResponse(
                p.getId(),
                new UomSummary(u.getId(), u.getUomCode(), u.getDescription(), u.getKind()),
                p.getQuantity(), p.getBarcode(),
                p.getWidth(), p.getDepth(), p.getHeight(), p.getDimensionUom(),
                p.getWeight(), p.getWeightUom());
    }
}
