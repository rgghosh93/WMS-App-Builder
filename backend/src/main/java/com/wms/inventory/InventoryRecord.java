package com.wms.inventory;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

import com.wms.facility.FacilityRecord;
import com.wms.masterdata.container.Container;
import com.wms.masterdata.item.Item;
import com.wms.masterdata.location.Location;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * A quantity of one item, in eaches, with its attributes (lot, serial, expiry,
 * status). It is either loose in a location or inside a container, never both.
 */
@Entity
@Table(name = "inventory")
public class InventoryRecord extends FacilityRecord {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "item_id", nullable = false)
    private Item item;

    @Column(nullable = false)
    private int quantity;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "location_id")
    private Location location;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "container_id")
    private Container container;

    @Column(name = "lot_number", length = 40)
    private String lotNumber;

    @Column(name = "serial_number", length = 60)
    private String serialNumber;

    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private InventoryStatus status;

    /** Why it is on hold or damaged. */
    @Column(name = "hold_reason", length = 400)
    private String holdReason;

    @Column(name = "held_at")
    private Instant heldAt;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected InventoryRecord() {
        // for JPA
    }

    public InventoryRecord(UUID id, Item item, int quantity) {
        this.id = id;
        this.item = item;
        this.quantity = quantity;
        this.status = InventoryStatus.AVAILABLE;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
        if (receivedAt == null) {
            receivedAt = now;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    /** Puts the stock loose in a location. */
    public void placeIn(Location location) {
        this.location = location;
        this.container = null;
    }

    /** Puts the stock inside a container. */
    public void placeIn(Container container) {
        this.container = container;
        this.location = null;
    }

    /** Takes it out of use, on hold or damaged. */
    public void hold(InventoryStatus as, String why) {
        status = as;
        holdReason = why;
        heldAt = Instant.now();
    }

    /** Makes it good to use again. */
    public void release() {
        status = InventoryStatus.AVAILABLE;
        holdReason = null;
        heldAt = null;
    }

    /** Some of it, as a record of its own. Not for serial numbers, which are one each. */
    public InventoryRecord copy(int quantity) {
        InventoryRecord copy = new InventoryRecord(UUID.randomUUID(), item, quantity);
        copy.lotNumber = lotNumber;
        copy.expiryDate = expiryDate;
        copy.status = status;
        copy.holdReason = holdReason;
        copy.heldAt = heldAt;
        copy.receivedAt = receivedAt;
        copy.location = location;
        copy.container = container;
        return copy;
    }

    /** The same stock as far as putting it together goes. */
    public boolean mergesWith(InventoryRecord other) {
        return serialNumber == null && other.serialNumber == null && item.getId().equals(other.item.getId())
                && Objects.equals(lotNumber, other.lotNumber) && Objects.equals(expiryDate, other.expiryDate) && status == other.status;
    }

    /** Past its expiry date. Stock can be used up to and including that date. */
    public boolean isExpired() {
        return expiryDate != null && expiryDate.isBefore(LocalDate.now());
    }

    /** Whether it may go to a customer: not on hold, damaged or expired. */
    public boolean isUsable() {
        return status == InventoryStatus.AVAILABLE && !isExpired();
    }

    public UUID getId() { return id; }
    public Item getItem() { return item; }
    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }
    public Location getLocation() { return location; }
    public Container getContainer() { return container; }
    public String getLotNumber() { return lotNumber; }
    public void setLotNumber(String lotNumber) { this.lotNumber = lotNumber; }
    public String getSerialNumber() { return serialNumber; }
    public void setSerialNumber(String serialNumber) { this.serialNumber = serialNumber; }
    public LocalDate getExpiryDate() { return expiryDate; }
    public void setExpiryDate(LocalDate expiryDate) { this.expiryDate = expiryDate; }
    public InventoryStatus getStatus() { return status; }
    public void setStatus(InventoryStatus status) { this.status = status; }
    public String getHoldReason() { return holdReason; }
    public Instant getHeldAt() { return heldAt; }
    public Instant getReceivedAt() { return receivedAt; }
    public void setReceivedAt(Instant receivedAt) { this.receivedAt = receivedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
}
