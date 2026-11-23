package com.wms.control;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.wms.facility.FacilityRecord;
import com.wms.inventory.InventoryStatus;
import com.wms.masterdata.item.Item;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** A correction of how much stock there is, made or waiting for approval. */
@Entity
@Table(name = "inventory_adjustments")
public class Adjustment extends FacilityRecord {

    public enum Status { PENDING, APPLIED, REJECTED }

    @Id
    private UUID id;

    @Column(nullable = false, length = 20)
    private String number;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status;

    /** The stock corrected; null when it was found where none was recorded. */
    @Column(name = "inventory_id")
    private UUID inventoryId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "item_id", nullable = false)
    private Item item;

    @Column(name = "location_id")
    private UUID locationId;

    @Column(name = "container_id")
    private UUID containerId;

    @Column(name = "lot_number", length = 40)
    private String lotNumber;

    @Column(name = "serial_number", length = 60)
    private String serialNumber;

    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "stock_status", nullable = false, length = 12)
    private InventoryStatus stockStatus;

    /** Where, in words. */
    @Column(nullable = false, length = 90)
    private String place;

    /** Eaches more, or less if negative. */
    @Column(nullable = false)
    private int change;

    @Column(nullable = false, length = 400)
    private String reason;

    @Column(name = "requested_by", nullable = false)
    private UUID requestedBy;

    @Column(nullable = false, length = 120)
    private String requester;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(length = 120)
    private String decider;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Version
    private long version;

    protected Adjustment() {
        // for JPA
    }

    Adjustment(String number, UUID inventoryId, Item item, UUID locationId, UUID containerId, String lotNumber, String serialNumber,
               LocalDate expiryDate, InventoryStatus stockStatus, String place, int change, String reason, Actor requester) {
        this.id = UUID.randomUUID();
        this.number = number;
        this.status = Status.PENDING;
        this.inventoryId = inventoryId;
        this.item = item;
        this.locationId = locationId;
        this.containerId = containerId;
        this.lotNumber = lotNumber;
        this.serialNumber = serialNumber;
        this.expiryDate = expiryDate;
        this.stockStatus = stockStatus;
        this.place = place;
        this.change = change;
        this.reason = reason;
        this.requestedBy = requester.id();
        this.requester = requester.name();
        this.requestedAt = Instant.now();
    }

    void applied() {
        status = Status.APPLIED;
    }

    void decide(Status as, Actor by) {
        status = as;
        decidedBy = by.id();
        decider = by.name();
        decidedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getNumber() { return number; }
    public Status getStatus() { return status; }
    public UUID getInventoryId() { return inventoryId; }
    public Item getItem() { return item; }
    public UUID getLocationId() { return locationId; }
    public UUID getContainerId() { return containerId; }
    public String getLotNumber() { return lotNumber; }
    public String getSerialNumber() { return serialNumber; }
    public LocalDate getExpiryDate() { return expiryDate; }
    public InventoryStatus getStockStatus() { return stockStatus; }
    public String getPlace() { return place; }
    public int getChange() { return change; }
    public String getReason() { return reason; }
    public UUID getRequestedBy() { return requestedBy; }
    public String getRequester() { return requester; }
    public Instant getRequestedAt() { return requestedAt; }
    public String getDecider() { return decider; }
    public Instant getDecidedAt() { return decidedAt; }
}
