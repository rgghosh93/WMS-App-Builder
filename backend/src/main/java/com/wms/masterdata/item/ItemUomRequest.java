package com.wms.masterdata.item;

import java.math.BigDecimal;
import java.util.UUID;

import com.wms.common.measure.DimensionUom;
import com.wms.common.measure.WeightUom;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * One packaging level in an item request.
 *
 * @param uomId    internal id of the unit of measure (not the base unit)
 * @param quantity how many eaches one of this unit holds
 */
public record ItemUomRequest(
        @NotNull UUID uomId,
        @NotNull @Min(value = 2, message = "must be at least 2 eaches") @Max(1_000_000) Integer quantity,
        @Size(max = 50) @Pattern(regexp = "^\\s*[\\x21-\\x7E]*\\s*$", message = "use printable characters with no spaces") String barcode,
        @Positive @DecimalMax(ItemRequest.MAX_DIMENSION) @Digits(integer = 8, fraction = 2) BigDecimal width,
        @Positive @DecimalMax(ItemRequest.MAX_DIMENSION) @Digits(integer = 8, fraction = 2) BigDecimal depth,
        @Positive @DecimalMax(ItemRequest.MAX_DIMENSION) @Digits(integer = 8, fraction = 2) BigDecimal height,
        DimensionUom dimensionUom,
        @Positive @DecimalMax(ItemRequest.MAX_WEIGHT) @Digits(integer = 9, fraction = 3) BigDecimal weight,
        WeightUom weightUom) {
}
