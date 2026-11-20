package com.wms.masterdata.location;

import com.wms.common.measure.DimensionUom;
import com.wms.common.measure.VolumeUom;
import com.wms.common.measure.WeightUom;
import java.math.BigDecimal;

import com.wms.common.Codes;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Body for creating or updating a location. Which identifiers are required
 * depends on {@link LocationType}; that check happens in {@link LocationService}.
 *
 * @param version the version the client last read; required on update so
 *                concurrent edits are detected
 */
public record LocationRequest(
        @NotBlank @Size(max = 40) @Pattern(regexp = CODE, message = CODE_MESSAGE) String locationId,
        @NotNull LocationType type,
        @Size(max = 20) @Pattern(regexp = CODE, message = CODE_MESSAGE) String area,
        @Size(max = 20) @Pattern(regexp = CODE, message = CODE_MESSAGE) String zone,
        @Size(max = 20) @Pattern(regexp = CODE, message = CODE_MESSAGE) String aisle,
        @Size(max = 20) @Pattern(regexp = CODE, message = CODE_MESSAGE) String bay,
        @Size(max = 20) @Pattern(regexp = CODE, message = CODE_MESSAGE) String level,
        @Size(max = 20) @Pattern(regexp = CODE, message = CODE_MESSAGE) String position,
        @NotNull @Positive @DecimalMax(MAX_DIMENSION) @Digits(integer = 8, fraction = 2) BigDecimal width,
        @NotNull @Positive @DecimalMax(MAX_DIMENSION) @Digits(integer = 8, fraction = 2) BigDecimal depth,
        @NotNull @Positive @DecimalMax(MAX_DIMENSION) @Digits(integer = 8, fraction = 2) BigDecimal height,
        @NotNull DimensionUom dimensionUom,
        @Positive @DecimalMax(MAX_WEIGHT) @Digits(integer = 9, fraction = 3) BigDecimal maxWeight,
        WeightUom maxWeightUom,
        @Positive @DecimalMax(MAX_VOLUME) @Digits(integer = 11, fraction = 3) BigDecimal maxVolume,
        VolumeUom maxVolumeUom,
        @Positive @Max(MAX_COUNT) Integer maxLpns,
        @Positive @Max(MAX_COUNT) Integer maxSkus,
        @Positive @Max(MAX_COUNT) Integer maxUnits,
        Long version) {

    /** Letters, digits, dash, underscore and dot; blank is allowed (requiredness is per type). */
    static final String CODE = "^\\s*$|^\\s*[A-Za-z0-9][A-Za-z0-9._-]*\\s*$";
    static final String CODE_MESSAGE = "use letters, numbers, '-', '_' or '.'";
    static final String MAX_DIMENSION = "99999999.99";
    static final String MAX_WEIGHT = "999999999.999";
    static final String MAX_VOLUME = "99999999999.999";
    static final long MAX_COUNT = 999_999_999;

    String identifier(LocationIdentifier identifier) {
        return switch (identifier) {
            case AREA -> area;
            case ZONE -> zone;
            case AISLE -> aisle;
            case BAY -> bay;
            case LEVEL -> level;
            case POSITION -> position;
        };
    }
}
