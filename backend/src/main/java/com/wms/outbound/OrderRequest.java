package com.wms.outbound;

import java.time.LocalDate;
import java.util.List;

import com.wms.common.Codes;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * A new order, as sent by an ERP or entered by hand.
 *
 * @param orderNumber optional; one is generated (SO001001...) when left out
 * @param priority    1 (most urgent) to 9; defaults to 5
 * @param shipTo      optional here; needed before it can ship
 */
public record OrderRequest(
        @Size(max = 40) @Pattern(regexp = Codes.PATTERN, message = Codes.MESSAGE) String orderNumber,
        @NotBlank @Size(max = 120) String customerName,
        LocalDate requestedShipDate,
        @Min(1) @Max(9) Integer priority,
        @NotEmpty(message = "add at least one line") @Size(max = 200) List<@Valid Line> lines,
        @Valid ShipTo shipTo) {

    /** An order whose ship-to address isn't known yet. */
    public OrderRequest(String orderNumber, String customerName, LocalDate requestedShipDate, Integer priority, List<Line> lines) {
        this(orderNumber, customerName, requestedShipDate, priority, lines, null);
    }

    /**
     * @param itemId       the item's code, e.g. SKU-10001
     * @param quantity     in eaches
     * @param requestedLot only allocate this lot (lot-tracked items only)
     */
    public record Line(
            @NotBlank String itemId,
            @NotNull @Min(1) @Max(1_000_000) Integer quantity,
            @Size(max = 40) String requestedLot) {
    }
}
