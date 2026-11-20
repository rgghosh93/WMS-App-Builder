package com.wms.masterdata.container;

import java.util.UUID;

import com.wms.common.Codes;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body for creating or updating a container.
 *
 * @param containerTypeId   internal id of the container type
 * @param currentLocationId internal id of the location it is at, or null if unknown
 * @param version           the version the client last read; required on update
 */
public record ContainerRequest(
        @NotBlank @Size(max = 40) @Pattern(regexp = Codes.PATTERN, message = Codes.MESSAGE) String lpnId,
        @NotNull UUID containerTypeId,
        @NotNull ContainerStatus status,
        UUID currentLocationId,
        @Size(max = 500) String notes,
        Long version) {
}
