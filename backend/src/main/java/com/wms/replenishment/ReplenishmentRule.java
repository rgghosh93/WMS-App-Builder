package com.wms.replenishment;

import java.util.UUID;

import com.wms.common.ActiveStatus;
import com.wms.facility.FacilityRecord;
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
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** Keeps an item stocked in a pick location: below the minimum, it is filled to the maximum. */
@Entity
@Table(name = "replenishment_rules")
public class ReplenishmentRule extends FacilityRecord {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "item_id", nullable = false)
    private Item item;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "location_id", nullable = false)
    private Location location;

    /** In eaches, as are the maximum and the multiple. */
    @Column(name = "min_quantity", nullable = false)
    private int minQuantity;

    @Column(name = "max_quantity", nullable = false)
    private int maxQuantity;

    /** Brought in multiples of this, such as a case. */
    @Column(nullable = false)
    private int multiple;

    /** Where stock comes from; any other storage location if null. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_zone_id")
    private Zone sourceZone;

    /** Whether it is also filled for orders that are short of the item. */
    @Column(name = "for_orders", nullable = false)
    private boolean forOrders;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ActiveStatus status;

    @Version
    private long version;

    protected ReplenishmentRule() {
        // for JPA
    }

    ReplenishmentRule(UUID id) {
        this.id = id;
    }

    public UUID getId() { return id; }
    public Item getItem() { return item; }
    void setItem(Item item) { this.item = item; }
    public Location getLocation() { return location; }
    void setLocation(Location location) { this.location = location; }
    public int getMinQuantity() { return minQuantity; }
    void setMinQuantity(int minQuantity) { this.minQuantity = minQuantity; }
    public int getMaxQuantity() { return maxQuantity; }
    void setMaxQuantity(int maxQuantity) { this.maxQuantity = maxQuantity; }
    public int getMultiple() { return multiple; }
    void setMultiple(int multiple) { this.multiple = multiple; }
    public Zone getSourceZone() { return sourceZone; }
    void setSourceZone(Zone sourceZone) { this.sourceZone = sourceZone; }
    public boolean isForOrders() { return forOrders; }
    void setForOrders(boolean forOrders) { this.forOrders = forOrders; }
    public ActiveStatus getStatus() { return status; }
    void setStatus(ActiveStatus status) { this.status = status; }
    public long getVersion() { return version; }
}
