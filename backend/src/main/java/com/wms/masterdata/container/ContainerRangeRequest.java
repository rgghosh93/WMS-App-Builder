package com.wms.masterdata.container;

import java.util.UUID;

import com.wms.common.Codes;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Creates a numbered run of containers in one step, e.g. prefix "TOTE", start 1,
 * count 500, digits 5 gives TOTE00001 to TOTE00500.
 */
public record ContainerRangeRequest(
        @Size(max = 30) @Pattern(regexp = Codes.PATTERN, message = Codes.MESSAGE) String prefix,
        @NotNull @Min(0) @Max(999_999_999) Long start,
        @NotNull @Min(1) @Max(MAX_COUNT) Integer count,
        @NotNull @Min(1) @Max(10) Integer digits,
        @NotNull UUID containerTypeId,
        @NotNull ContainerStatus status,
        UUID currentLocationId,
        @Size(max = 500) String notes) {

    public static final int MAX_COUNT = 1000;
}
