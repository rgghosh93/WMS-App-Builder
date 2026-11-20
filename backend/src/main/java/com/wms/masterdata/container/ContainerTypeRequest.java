package com.wms.masterdata.container;

import java.math.BigDecimal;

import com.wms.common.ActiveStatus;
import com.wms.common.Codes;
import com.wms.common.measure.DimensionUom;
import com.wms.common.measure.VolumeUom;
import com.wms.common.measure.WeightUom;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Body for creating or updating a container type. Value/unit pairs are checked
 * in {@link ContainerTypeService}.
 *
 * @param reusable optional; missing means single-use
 * @param version  the version the client last read; required on update
 */
public record ContainerTypeRequest(
        @NotBlank @Size(max = 40) @Pattern(regexp = Codes.PATTERN, message = Codes.MESSAGE) String containerTypeId,
        @NotBlank @Size(max = 200) String description,
        @NotNull ContainerCategory category,
        @NotNull ActiveStatus status,
        Boolean reusable,
        @NotNull @Positive @DecimalMax(MAX_DIMENSION) @Digits(integer = 8, fraction = 2) BigDecimal width,
        @NotNull @Positive @DecimalMax(MAX_DIMENSION) @Digits(integer = 8, fraction = 2) BigDecimal depth,
        @NotNull @Positive @DecimalMax(MAX_DIMENSION) @Digits(integer = 8, fraction = 2) BigDecimal height,
        @NotNull DimensionUom dimensionUom,
        @Positive @DecimalMax(MAX_WEIGHT) @Digits(integer = 9, fraction = 3) BigDecimal tareWeight,
        WeightUom tareWeightUom,
        @Positive @DecimalMax(MAX_WEIGHT) @Digits(integer = 9, fraction = 3) BigDecimal maxWeight,
        WeightUom maxWeightUom,
        @Positive @DecimalMax(MAX_VOLUME) @Digits(integer = 11, fraction = 3) BigDecimal maxVolume,
        VolumeUom maxVolumeUom,
        Long version) {

    static final String MAX_DIMENSION = "99999999.99";
    static final String MAX_WEIGHT = "999999999.999";
    static final String MAX_VOLUME = "99999999999.999";

    boolean isReusable() {
        return Boolean.TRUE.equals(reusable);
    }
}
