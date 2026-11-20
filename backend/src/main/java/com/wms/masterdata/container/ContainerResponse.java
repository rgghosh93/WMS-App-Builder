package com.wms.masterdata.container;

import java.time.Instant;
import java.util.UUID;

import com.wms.masterdata.location.Location;

/**
 * @param parentContainer the container this one is inside, if any
 * @param permanent       facility-owned container (true) or an LPN created with inventory (false)
 */
public record ContainerResponse(
        UUID id,
        String lpnId,
        TypeSummary containerType,
        ContainerStatus status,
        LocationSummary currentLocation,
        ParentSummary parentContainer,
        boolean permanent,
        String notes,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    /** Enough of the container type to show it without another call. */
    public record TypeSummary(UUID id, String containerTypeId, String description, ContainerCategory category) {

        public static TypeSummary of(ContainerType t) {
            return new TypeSummary(t.getId(), t.getContainerTypeId(), t.getDescription(), t.getCategory());
        }
    }

    public record LocationSummary(UUID id, String locationId) {
    }

    public record ParentSummary(UUID id, String lpnId) {
    }

    static ContainerResponse from(Container c) {
        Location l = c.getCurrentLocation();
        Container p = c.getParent();
        return new ContainerResponse(
                c.getId(),
                c.getLpnId(),
                TypeSummary.of(c.getContainerType()),
                c.getStatus(),
                l == null ? null : new LocationSummary(l.getId(), l.getLocationId()),
                p == null ? null : new ParentSummary(p.getId(), p.getLpnId()),
                c.isPermanent(),
                c.getNotes(),
                c.getCreatedAt(),
                c.getUpdatedAt(),
                c.getVersion());
    }
}
