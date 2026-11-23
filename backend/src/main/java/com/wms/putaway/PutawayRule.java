package com.wms.putaway;

import java.time.Instant;
import java.util.UUID;

import com.wms.common.ActiveStatus;
import com.wms.inventory.InventoryStatus;
import com.wms.masterdata.container.ContainerCategory;
import com.wms.masterdata.item.Item;
import com.wms.masterdata.location.Location;
import com.wms.masterdata.zone.Zone;

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

/** Which LPNs go where. Rules are tried in sequence; the first that fits and has room decides. */
@Entity
@Table(name = "putaway_rules")
public class PutawayRule {

    @Id
    private UUID id;

    @Column(name = "sequence_number", nullable = false)
    private int sequence;

    @Column(nullable = false, length = 120)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ActiveStatus status;

    /** Only LPNs holding nothing but this item; any if null. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "item_id")
    private Item item;

    /** Only this kind of container; any if null. */
    @Enumerated(EnumType.STRING)
    @Column(name = "container_category", length = 20)
    private ContainerCategory containerCategory;

    /** Only LPNs holding some stock in this status; any if null. */
    @Enumerated(EnumType.STRING)
    @Column(name = "stock_status", length = 12)
    private InventoryStatus stockStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PutawayStrategy strategy;

    /** For FIXED. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "location_id")
    private Location location;

    /** A put-away zone, for ZONE and optionally NEAREST_EMPTY. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "zone_id")
    private Zone zone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private PutawayHandling handling;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected PutawayRule() {
        // for JPA
    }

    PutawayRule(UUID id) {
        this.id = id;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public int getSequence() { return sequence; }
    public void setSequence(int sequence) { this.sequence = sequence; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public ActiveStatus getStatus() { return status; }
    public void setStatus(ActiveStatus status) { this.status = status; }
    public Item getItem() { return item; }
    public void setItem(Item item) { this.item = item; }
    public ContainerCategory getContainerCategory() { return containerCategory; }
    public void setContainerCategory(ContainerCategory containerCategory) { this.containerCategory = containerCategory; }
    public InventoryStatus getStockStatus() { return stockStatus; }
    public void setStockStatus(InventoryStatus stockStatus) { this.stockStatus = stockStatus; }
    public PutawayStrategy getStrategy() { return strategy; }
    public void setStrategy(PutawayStrategy strategy) { this.strategy = strategy; }
    public Location getLocation() { return location; }
    public void setLocation(Location location) { this.location = location; }
    public Zone getZone() { return zone; }
    public void setZone(Zone zone) { this.zone = zone; }
    public PutawayHandling getHandling() { return handling; }
    public void setHandling(PutawayHandling handling) { this.handling = handling; }
    public long getVersion() { return version; }
}
