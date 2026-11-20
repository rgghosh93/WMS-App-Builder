package com.wms.outbound;

import java.time.Instant;
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
 * One step of a pick task, made from one allocation: take a quantity of an item from a
 * location, and from a specific LPN if the stock is in one.
 */
@Entity
@Table(name = "pick_task_details")
public class PickTaskDetail {

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
    private PickTaskStatus status;

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
        this.status = PickTaskStatus.OPEN;
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
        status = PickTaskStatus.CANCELLED;
        allocation = null;
    }

    public UUID getId() { return id; }
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
    public PickTaskStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
}
