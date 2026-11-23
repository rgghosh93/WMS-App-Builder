package com.wms.putaway;

import java.util.UUID;

import com.wms.common.ActiveStatus;
import com.wms.inventory.InventoryStatus;
import com.wms.masterdata.container.ContainerCategory;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * @param locationId for FIXED
 * @param zoneId     a put-away zone, for ZONE and optionally NEAREST_EMPTY
 * @param version    when updating
 */
public record PutawayRuleRequest(
        @NotNull @Min(1) @Max(9999) Integer sequence,
        @NotBlank @Size(max = 120) String name,
        @NotNull ActiveStatus status,
        UUID itemId,
        ContainerCategory containerCategory,
        InventoryStatus stockStatus,
        @NotNull PutawayStrategy strategy,
        UUID locationId,
        UUID zoneId,
        @NotNull PutawayHandling handling,
        Long version) {
}
