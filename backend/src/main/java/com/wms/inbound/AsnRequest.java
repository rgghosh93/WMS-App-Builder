package com.wms.inbound;

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
 * A new ASN, as sent by a vendor or entered by hand.
 *
 * @param asnNumber optional; one is generated (AS000001...) when left out
 * @param owner     code of whose goods they are; the first owner if left out
 * @param reference the vendor's or carrier's own number
 */
public record AsnRequest(
        @Size(max = 40) @Pattern(regexp = Codes.PATTERN, message = Codes.MESSAGE) String asnNumber,
        @NotBlank @Size(max = 120) String vendorName,
        @Size(max = 60) String reference,
        LocalDate expectedDate,
        @NotEmpty(message = "add at least one line") @Size(max = 500) List<@Valid @NotNull Line> lines,
        @Size(max = 20) String owner) {

    /** Of the first owner. */
    public AsnRequest(String asnNumber, String vendorName, String reference, LocalDate expectedDate, List<Line> lines) {
        this(asnNumber, vendorName, reference, expectedDate, lines, null);
    }

    /**
     * @param quantity   in eaches
     * @param lotNumber  for lot-tracked items, if known
     * @param expiryDate for expiry-tracked items, if known
     * @param lpnId      the LPN the vendor packed this in, if known
     */
    public record Line(
            @NotBlank String itemId,
            @NotNull @Min(1) @Max(1_000_000) Integer quantity,
            @Size(max = 40) String lotNumber,
            LocalDate expiryDate,
            @Size(max = 40) @Pattern(regexp = Codes.PATTERN, message = Codes.MESSAGE) String lpnId) {
    }
}
