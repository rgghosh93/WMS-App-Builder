package com.wms.masterdata.uom;

import com.wms.common.ActiveStatus;
import com.wms.common.Codes;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Body for creating or updating a unit of measure. */
public record UomRequest(
        @NotBlank @Size(max = 10) @Pattern(regexp = Codes.PATTERN, message = Codes.MESSAGE) String uomCode,
        @NotBlank @Size(max = 100) String description,
        @NotNull UomKind kind,
        @NotNull ActiveStatus status,
        Long version) {
}
