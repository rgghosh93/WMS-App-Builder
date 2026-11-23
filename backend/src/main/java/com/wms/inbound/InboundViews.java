package com.wms.inbound;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.wms.inventory.InventoryStatus;
import com.wms.masterdata.container.ContainerType;
import com.wms.masterdata.item.Item;
import com.wms.masterdata.item.ItemScan;
import com.wms.masterdata.location.Location;

/** Read models for ASNs and receiving. */
public final class InboundViews {

    private InboundViews() {
    }

    /** What a receiver has to know about an item. */
    public record ItemInfo(UUID id, String itemId, String description, boolean lotControlled, boolean serialControlled,
                           boolean expiryControlled, Integer shelfLifeDays, List<ItemScan> scans) {
        static ItemInfo of(Item i) {
            return new ItemInfo(i.getId(), i.getItemId(), i.getDescription(), i.isLotControlled(), i.isSerialControlled(),
                    i.isExpiryControlled(), i.getShelfLifeDays(), ItemScan.of(i));
        }
    }

    public record LineView(UUID id, int lineNumber, ItemInfo item, int expectedQuantity, int receivedQuantity, int remaining,
                           String lotNumber, LocalDate expiryDate, String lpnId) {
        static LineView of(AsnLine l) {
            return new LineView(l.getId(), l.getLineNumber(), ItemInfo.of(l.getItem()), l.getExpectedQuantity(),
                    l.getReceivedQuantity(), l.remaining(), l.getLotNumber(), l.getExpiryDate(), l.getLpnId());
        }
    }

    public record LpnRef(UUID id, String lpnId, String containerType) {
    }

    /** @param type what kind of location it is, in words */
    public record LocationRef(UUID id, String locationId, String type) {
        static LocationRef of(Location l) {
            return new LocationRef(l.getId(), l.getLocationId(), l.getType().label());
        }
    }

    public record ReceiptView(UUID id, int lineNumber, String itemId, String description, int quantity, String lotNumber,
                              String serialNumber, LocalDate expiryDate, InventoryStatus condition, LpnRef lpn,
                              LocationRef location, String receivedBy, Instant receivedAt, String reversedBy,
                              Instant reversedAt) {
        static ReceiptView of(AsnReceipt r) {
            return new ReceiptView(r.getId(), r.getLine().getLineNumber(), r.getLine().getItem().getItemId(),
                    r.getLine().getItem().getDescription(), r.getQuantity(), r.getLotNumber(), r.getSerialNumber(),
                    r.getExpiryDate(), r.getInventoryStatus(),
                    new LpnRef(r.getContainer().getId(), r.getContainer().getLpnId(),
                            r.getContainer().getContainerType().getContainerTypeId()),
                    LocationRef.of(r.getLocation()),
                    r.getReceivedBy().getDisplayName(), r.getReceivedAt(),
                    r.getReversedBy() == null ? null : r.getReversedBy().getDisplayName(), r.getReversedAt());
        }
    }

    public record EventView(Instant occurredAt, AsnEvent.Type type, String message) {
        static EventView of(AsnEvent e) {
            return new EventView(e.getOccurredAt(), e.getType(), e.getMessage());
        }
    }

    /** One ASN in the list. */
    public record AsnSummary(UUID id, String asnNumber, String vendorName, String reference, LocalDate expectedDate,
                             AsnStatus status, int lineCount, long expectedQuantity, long receivedQuantity,
                             Instant createdAt, Instant closedAt) {
        static AsnSummary of(Asn a) {
            return new AsnSummary(a.getId(), a.getAsnNumber(), a.getVendorName(), a.getReference(), a.getExpectedDate(),
                    a.getStatus(), a.getLines().size(),
                    a.getLines().stream().mapToLong(AsnLine::getExpectedQuantity).sum(),
                    a.getLines().stream().mapToLong(AsnLine::getReceivedQuantity).sum(), a.getCreatedAt(), a.getClosedAt());
        }
    }

    /** An ASN with its lines, what has been received, and what has happened. */
    public record AsnDetail(UUID id, String asnNumber, String vendorName, String reference, LocalDate expectedDate,
                            AsnStatus status, long expectedQuantity, long receivedQuantity, List<LineView> lines,
                            List<ReceiptView> receipts, List<EventView> events, String closedBy, Instant createdAt,
                            Instant closedAt) {
    }

    public record ContainerTypeOption(UUID id, String containerTypeId, String description) {
        static ContainerTypeOption of(ContainerType t) {
            return new ContainerTypeOption(t.getId(), t.getContainerTypeId(), t.getDescription());
        }
    }

    /** What an LPN about to be received into is. */
    public enum LpnKind {
        /** Not seen before: its type has to be chosen. */
        NEW,
        /** The vendor announced it on this ASN, and it hasn't been received. */
        ANNOUNCED,
        /** A container that can take (more of) this ASN. */
        OPEN
    }

    /**
     * @param needsType     the LPN is new, so its container type has to be chosen
     * @param expected      what the vendor said is in it and hasn't been received
     * @param whyNotAll     why what is in it can't be received in one go, or null if it can
     */
    public record LpnCheck(String lpnId, LpnKind kind, boolean needsType, String containerType, List<LineView> expected,
                           String whyNotAll, List<ContainerTypeOption> types) {
    }
}
