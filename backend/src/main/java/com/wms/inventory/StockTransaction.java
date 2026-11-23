package com.wms.inventory;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.hibernate.annotations.Immutable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One change to stock, kept for good. Without a from-place stock came into the warehouse, and
 * without a to-place it left, so what is on hand is always what came in less what left.
 */
@Entity
@Immutable
@Table(name = "inventory_transactions")
public class StockTransaction {

    public enum Type {
        /** On hand when the history began. */
        OPENING,
        RECEIVE,
        RECEIVE_UNDO,
        PUTAWAY,
        MOVE,
        REPLENISH,
        PICK,
        PACK,
        UNPACK,
        SHIP,
        HOLD,
        RELEASE,
        ADJUST,
        COUNT
    }

    @Id
    private UUID id;

    @Column(nullable = false)
    private long number;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Type type;

    @Column(name = "item_id", nullable = false)
    private UUID itemId;

    @Column(name = "item_code", nullable = false, length = 40)
    private String itemCode;

    @Column(name = "lot_number", length = 40)
    private String lotNumber;

    @Column(name = "serial_number", length = 60)
    private String serialNumber;

    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "from_location", length = 40)
    private String fromLocation;

    @Column(name = "from_lpn", length = 40)
    private String fromLpn;

    @Column(name = "to_location", length = 40)
    private String toLocation;

    @Column(name = "to_lpn", length = 40)
    private String toLpn;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 12)
    private InventoryStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", length = 12)
    private InventoryStatus toStatus;

    @Column(length = 120)
    private String reference;

    @Column(length = 400)
    private String reason;

    @Column(name = "request_id")
    private UUID requestId;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "user_name", nullable = false, length = 120)
    private String userName;

    protected StockTransaction() {
        // for JPA
    }

    StockTransaction(long number, Type type, InventoryRecord what, int quantity, StockLedger.Place from, StockLedger.Place to,
                     InventoryStatus fromStatus, InventoryStatus toStatus, StockLedger.Note note, String userName) {
        this.id = UUID.randomUUID();
        this.number = number;
        this.occurredAt = Instant.now();
        this.type = type;
        this.itemId = what.getItem().getId();
        this.itemCode = what.getItem().getItemId();
        this.lotNumber = what.getLotNumber();
        this.serialNumber = what.getSerialNumber();
        this.expiryDate = what.getExpiryDate();
        this.quantity = quantity;
        this.fromLocation = from.location();
        this.fromLpn = from.lpn();
        this.toLocation = to.location();
        this.toLpn = to.lpn();
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.reference = note.reference();
        this.reason = note.reason() == null || note.reason().length() <= 400 ? note.reason() : note.reason().substring(0, 400);
        this.requestId = note.requestId();
        this.userId = note.userId();
        this.userName = userName;
    }

    public UUID getId() { return id; }
    public long getNumber() { return number; }
    public Instant getOccurredAt() { return occurredAt; }
    public Type getType() { return type; }
    public UUID getItemId() { return itemId; }
    public String getItemCode() { return itemCode; }
    public String getLotNumber() { return lotNumber; }
    public String getSerialNumber() { return serialNumber; }
    public LocalDate getExpiryDate() { return expiryDate; }
    public int getQuantity() { return quantity; }
    public String getFromLocation() { return fromLocation; }
    public String getFromLpn() { return fromLpn; }
    public String getToLocation() { return toLocation; }
    public String getToLpn() { return toLpn; }
    public InventoryStatus getFromStatus() { return fromStatus; }
    public InventoryStatus getToStatus() { return toStatus; }
    public String getReference() { return reference; }
    public String getReason() { return reason; }
    public String getUserName() { return userName; }
}
