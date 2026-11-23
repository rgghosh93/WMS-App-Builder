package com.wms.control;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.wms.inventory.InventoryRecord;
import com.wms.inventory.InventoryStatus;
import com.wms.inventory.StockLedger;
import com.wms.inventory.StockTransaction;
import com.wms.masterdata.item.Item;
import com.wms.masterdata.item.ItemScan;
import com.wms.masterdata.location.Location;

/** Read models for inventory control. */
public final class ControlViews {

    private ControlViews() {
    }

    public record ItemRef(UUID id, String itemId, String description, boolean lotControlled, boolean serialControlled,
                          boolean expiryControlled) {
        static ItemRef of(Item i) {
            return new ItemRef(i.getId(), i.getItemId(), i.getDescription(), i.isLotControlled(), i.isSerialControlled(),
                    i.isExpiryControlled());
        }
    }

    /**
     * One inventory record.
     *
     * @param location null while someone is carrying it
     * @param reserved eaches reserved for orders
     */
    public record StockView(UUID id, ItemRef item, int quantity, int reserved, String lotNumber, String serialNumber,
                            LocalDate expiryDate, InventoryStatus status, String holdReason, Instant heldAt, boolean expired,
                            UUID locationId, String location, String locationType, UUID containerId, String lpn, Instant receivedAt) {
        static StockView of(InventoryRecord r, int reserved) {
            Location at = StockLedger.locationOf(r);
            return new StockView(r.getId(), ItemRef.of(r.getItem()), r.getQuantity(), reserved, r.getLotNumber(), r.getSerialNumber(),
                    r.getExpiryDate(), r.getStatus(), r.getHoldReason(), r.getHeldAt(), r.isExpired(),
                    at == null ? null : at.getId(), at == null ? null : at.getLocationId(), at == null ? null : at.getType().name(),
                    r.getContainer() == null ? null : r.getContainer().getId(),
                    r.getContainer() == null ? null : r.getContainer().getLpnId(), r.getReceivedAt());
        }
    }

    /**
     * How much there is of an item, or of one lot of it.
     *
     * @param available in storage, good, and not reserved: what an order could get now
     * @param elsewhere good but not for orders now, and not reserved: being received, carried or packed, or in a zone kept from orders
     */
    public record OnHand(String lotNumber, LocalDate expiryDate, long total, long available, long reserved, long onHold, long damaged,
                         long expired, long elsewhere) {
    }

    public record ItemOnHand(ItemRef item, OnHand total, List<OnHand> lots) {
    }

    public record TransactionView(UUID id, long number, Instant occurredAt, StockTransaction.Type type, UUID itemId, String itemCode,
                                  String lotNumber, String serialNumber, LocalDate expiryDate, int quantity, String fromLocation,
                                  String fromLpn, String toLocation, String toLpn, InventoryStatus fromStatus,
                                  InventoryStatus toStatus, String reference, String reason, String userName) {
        static TransactionView of(StockTransaction t) {
            return new TransactionView(t.getId(), t.getNumber(), t.getOccurredAt(), t.getType(), t.getItemId(), t.getItemCode(),
                    t.getLotNumber(), t.getSerialNumber(), t.getExpiryDate(), t.getQuantity(), t.getFromLocation(), t.getFromLpn(),
                    t.getToLocation(), t.getToLpn(), t.getFromStatus(), t.getToStatus(), t.getReference(), t.getReason(), t.getUserName());
        }
    }

    /**
     * @param stuck reserved for picks already under way or done, so it stays with its order but can't be picked, packed or shipped
     */
    public record HoldResult(int records, int quantity, int ordersReallocated, int stuck) {
    }

    /** Loose stock at a location that could be moved. */
    public record MovableLine(UUID inventoryId, ItemRef item, List<ItemScan> scans, String lotNumber, String serialNumber,
                              LocalDate expiryDate, InventoryStatus status, int quantity, int free) {
    }

    /**
     * What a scanned label to move from is.
     *
     * @param lpn   set for an LPN, which is moved whole
     * @param lines what is in the LPN, or lies loose at the location
     */
    public record MoveSource(String lpn, String containerType, String location, List<MovableLine> lines) {
    }

    public record MoveResult(String message) {
    }
}
