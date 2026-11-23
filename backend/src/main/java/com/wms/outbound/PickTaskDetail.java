package com.wms.outbound;

import java.time.Instant;
import java.util.UUID;

import com.wms.facility.FacilityRecord;
import com.wms.masterdata.container.Container;
import com.wms.masterdata.item.Item;
import com.wms.masterdata.location.Location;
import com.wms.users.AppUser;

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
 * One step of a pick task, made from one allocation: take a quantity of an item from a
 * location, and from a specific LPN if the stock is in one.
 */
@Entity
@Table(name = "pick_task_details")
public class PickTaskDetail extends FacilityRecord {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pick_task_id", nullable = false)
    private PickTask task;

    @Column(name = "detail_number", nullable = false)
    private int detailNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private OutboundOrder order;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_line_id", nullable = false)
    private OrderLine line;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "allocation_id")
    private Allocation allocation;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "item_id", nullable = false)
    private Item item;

    @Column(nullable = false)
    private int quantity;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "from_location_id", nullable = false)
    private Location fromLocation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "from_container_id")
    private Container fromContainer;

    @Column(name = "lot_number", length = 40)
    private String lotNumber;

    @Column(name = "serial_number", length = 60)
    private String serialNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PickDetailStatus status;

    @Column(name = "picked_quantity", nullable = false)
    private int pickedQuantity;

    /** The reservation of the stock already picked, now in the outbound container. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "picked_allocation_id")
    private Allocation pickedAllocation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "picked_by")
    private AppUser pickedBy;

    @Column(name = "picked_at")
    private Instant pickedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected PickTaskDetail() {
        // for JPA
    }

    PickTaskDetail(UUID id, PickTask task, int detailNumber, Allocation allocation, Location fromLocation,
                   Container fromContainer) {
        this.id = id;
        this.task = task;
        this.detailNumber = detailNumber;
        this.allocation = allocation;
        this.line = allocation.getLine();
        this.order = line.getOrder();
        this.item = line.getItem();
        this.quantity = allocation.getQuantity();
        this.fromLocation = fromLocation;
        this.fromContainer = fromContainer;
        this.lotNumber = allocation.getInventory().getLotNumber();
        this.serialNumber = allocation.getInventory().getSerialNumber();
        this.status = PickDetailStatus.OPEN;
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

    /** No longer needed (e.g. the order was cancelled); the allocation it picked is released. */
    void cancel() {
        status = PickDetailStatus.CANCELLED;
        allocation = null;
    }

    /** What was picked isn't wanted any more; it stays in the container it was picked into until someone puts it away. */
    void undo() {
        status = PickDetailStatus.CANCELLED;
        allocation = null;
        pickedAllocation = null;
    }

    public int remaining() {
        return quantity - pickedQuantity;
    }

    void recordPick(int eaches, AppUser user) {
        pickedQuantity += eaches;
        pickedBy = user;
        pickedAt = Instant.now();
    }

    void close(PickDetailStatus status) {
        this.status = status;
    }

    /** The whole reservation went into the outbound container as it was. */
    void moveAllocation() {
        pickedAllocation = allocation;
        allocation = null;
    }

    /** Nothing is left to pick from the original reservation. */
    void clearAllocation() {
        allocation = null;
    }

    /** The reservation is gone: its stock was split into cartons or reported missing. */
    void forgetPicked(Allocation allocation) {
        if (pickedAllocation != null && pickedAllocation.getId().equals(allocation.getId())) {
            pickedAllocation = null;
        }
    }

    void setPickedAllocation(Allocation pickedAllocation) {
        this.pickedAllocation = pickedAllocation;
    }

    public UUID getId() { return id; }
    public int getPickedQuantity() { return pickedQuantity; }
    public Allocation getPickedAllocation() { return pickedAllocation; }
    public AppUser getPickedBy() { return pickedBy; }
    public Instant getPickedAt() { return pickedAt; }
    public PickTask getTask() { return task; }
    public int getDetailNumber() { return detailNumber; }
    public OutboundOrder getOrder() { return order; }
    public OrderLine getLine() { return line; }
    public Allocation getAllocation() { return allocation; }
    public Item getItem() { return item; }
    public int getQuantity() { return quantity; }
    public Location getFromLocation() { return fromLocation; }
    public Container getFromContainer() { return fromContainer; }
    public String getLotNumber() { return lotNumber; }
    public String getSerialNumber() { return serialNumber; }
    public PickDetailStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
}
