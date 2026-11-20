package com.wms.inventory;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.wms.masterdata.container.ContainerResponse.TypeSummary;
import com.wms.masterdata.container.ContainerStatus;
import com.wms.masterdata.item.Item;
import com.wms.masterdata.location.Location;
import com.wms.masterdata.location.LocationType;

/** Read models for viewing inventory by location. */
public final class InventoryViews {

    private InventoryViews() {
    }

    public record LocationRef(UUID id, String locationId, LocationType type, String area, String zone) {

        static LocationRef of(Location l) {
            return new LocationRef(l.getId(), l.getLocationId(), l.getType(), l.getArea(), l.getZone());
        }
    }

    /**
     * One location in the list, with totals that include everything inside its containers.
     *
     * @param totalQuantity eaches in the location, loose and in containers
     * @param itemCount     distinct items
     * @param containerCount LPNs in the location, including nested ones
     * @param looseLineCount inventory records lying loose (not in a container)
     */
    public record LocationSummary(
            LocationRef location,
            long totalQuantity,
            int itemCount,
            int containerCount,
            int looseLineCount) {
    }

    /** Everything in one location: loose stock, then containers as a tree. */
    public record LocationDetail(
            LocationRef location,
            long totalQuantity,
            int itemCount,
            int containerCount,
            List<InventoryLine> lines,
            List<ContainerNode> containers) {
    }

    /**
     * A container with its own stock and the containers inside it.
     *
     * @param totalQuantity eaches in this container and everything inside it
     */
    public record ContainerNode(
            UUID id,
            String lpnId,
            boolean permanent,
            TypeSummary containerType,
            ContainerStatus status,
            long totalQuantity,
            int itemCount,
            List<InventoryLine> lines,
            List<ContainerNode> children) {
    }

    public record LpnRef(UUID id, String lpnId) {
    }

    /**
     * One container in the container list.
     *
     * @param location      where it is: its own location, or that of the outermost container it is inside
     * @param path          containers it sits inside, outermost first (empty if none)
     * @param totalQuantity eaches in it and everything inside it
     * @param nestedCount   containers inside it, at any depth
     * @param lineCount     inventory records held directly in it
     */
    public record ContainerSummary(
            UUID id,
            String lpnId,
            boolean permanent,
            TypeSummary containerType,
            ContainerStatus status,
            LocationRef location,
            List<LpnRef> path,
            long totalQuantity,
            int itemCount,
            int nestedCount,
            int lineCount) {
    }

    /** One container with everything inside it, and where it is. */
    public record ContainerDetail(LocationRef location, List<LpnRef> path, ContainerNode container) {
    }

    /** An item's packaging level, for showing quantities as cases/pallets. */
    public record Pack(String uomCode, int quantity) {
    }

    public record ItemRef(UUID id, String itemId, String description, List<Pack> packs) {

        static ItemRef of(Item i) {
            return new ItemRef(i.getId(), i.getItemId(), i.getDescription(),
                    i.getUoms().stream().map(p -> new Pack(p.getUom().getUomCode(), p.getQuantity())).toList());
        }
    }

    /** @param allocatedQuantity eaches of this record reserved for orders */
    public record InventoryLine(
            UUID id,
            ItemRef item,
            int quantity,
            int allocatedQuantity,
            String lotNumber,
            String serialNumber,
            LocalDate expiryDate,
            InventoryStatus status,
            Instant receivedAt) {

        static InventoryLine of(InventoryRecord r, int allocated) {
            return new InventoryLine(r.getId(), ItemRef.of(r.getItem()), r.getQuantity(), allocated, r.getLotNumber(),
                    r.getSerialNumber(), r.getExpiryDate(), r.getStatus(), r.getReceivedAt());
        }
    }
}
