package com.wms.inventory;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

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
public class InventoryRecord {

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
    public Instant getReceivedAt() { return receivedAt; }
    public void setReceivedAt(Instant receivedAt) { this.receivedAt = receivedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
}
