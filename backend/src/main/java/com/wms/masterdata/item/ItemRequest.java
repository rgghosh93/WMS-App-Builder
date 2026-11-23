package com.wms.masterdata.item;

import java.math.BigDecimal;
import java.util.List;

import com.wms.common.ActiveStatus;
import com.wms.common.Codes;
import com.wms.common.measure.DimensionUom;
import com.wms.common.measure.WeightUom;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Body for creating or updating an item. Rules that span fields (dimensions
 * all-or-nothing, shelf life only with expiry tracking) are checked in {@link ItemService}.
 *
 * @param uoms    packaging levels above the each (case, pallet...). On update, null
 *                leaves them unchanged and a list replaces them.
 * @param version the version the client last read; required on update
 */
public record ItemRequest(
        @NotBlank @Size(max = 40) @Pattern(regexp = Codes.PATTERN, message = Codes.MESSAGE) String itemId,
        @NotBlank @Size(max = 200) String description,
        @NotNull ActiveStatus status,
        @Size(max = 50) @Pattern(regexp = "^\\s*[\\x21-\\x7E]*\\s*$", message = "use printable characters with no spaces") String barcode,
        Boolean lotControlled,
        Boolean serialControlled,
        Boolean expiryControlled,
        @Positive @Max(36500) Integer shelfLifeDays,
        @Positive @DecimalMax(MAX_DIMENSION) @Digits(integer = 8, fraction = 2) BigDecimal width,
        @Positive @DecimalMax(MAX_DIMENSION) @Digits(integer = 8, fraction = 2) BigDecimal depth,
        @Positive @DecimalMax(MAX_DIMENSION) @Digits(integer = 8, fraction = 2) BigDecimal height,
        DimensionUom dimensionUom,
        @Positive @DecimalMax(MAX_WEIGHT) @Digits(integer = 9, fraction = 3) BigDecimal weight,
        WeightUom weightUom,
        @Valid @Size(max = 10, message = "at most 10 packaging levels") List<ItemUomRequest> uoms,
        @Size(max = 20) String owner,
        Long version) {

    static final String MAX_DIMENSION = "99999999.99";
    static final String MAX_WEIGHT = "999999999.999";

    /** Tracking flags are optional in the request; missing means not tracked. */
    boolean tracksLots() {
        return Boolean.TRUE.equals(lotControlled);
    }

    boolean tracksSerials() {
        return Boolean.TRUE.equals(serialControlled);
    }

    boolean tracksExpiry() {
        return Boolean.TRUE.equals(expiryControlled);
    }
}
