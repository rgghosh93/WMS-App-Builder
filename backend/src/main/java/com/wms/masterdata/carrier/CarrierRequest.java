package com.wms.masterdata.carrier;

import java.util.List;
import java.util.UUID;

import com.wms.common.ActiveStatus;
import com.wms.common.Codes;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Body for creating or updating a carrier with all of its services. */
public record CarrierRequest(
        @NotBlank @Size(max = 20) @Pattern(regexp = Codes.PATTERN, message = Codes.MESSAGE) String carrierCode,
        @NotBlank @Size(max = 120) String name,
        @NotNull CarrierMode mode,
        @NotNull Boolean trackingRequired,
        @NotNull ActiveStatus status,
        @NotEmpty(message = "add at least one service") @Size(max = 50) List<@Valid @NotNull Service> services,
        Long version) {

    /** @param id the existing service, or null for a new one */
    public record Service(
            UUID id,
            @NotBlank @Size(max = 20) @Pattern(regexp = Codes.PATTERN, message = Codes.MESSAGE) String serviceCode,
            @NotBlank @Size(max = 120) String name,
            @NotNull ActiveStatus status) {
    }
}
