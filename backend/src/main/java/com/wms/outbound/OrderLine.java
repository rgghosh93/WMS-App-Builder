package com.wms.outbound;

import java.util.UUID;

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

/** One item on an order, in eaches, with how much of it has stock reserved. */
@Entity
@Table(name = "order_lines")
public class OrderLine {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private OutboundOrder order;

    @Column(name = "line_number", nullable = false)
    private int lineNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "item_id", nullable = false)
    private Item item;

    @Column(name = "ordered_quantity", nullable = false)
    private int orderedQuantity;

    @Column(name = "allocated_quantity", nullable = false)
    private int allocatedQuantity;

    @Column(name = "picked_quantity", nullable = false)
    private int pickedQuantity;

    /** Only allocate this lot, if set. */
    @Column(name = "requested_lot", length = 40)
    private String requestedLot;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private LineStatus status;

    protected OrderLine() {
        // for JPA
    }

    OrderLine(UUID id, OutboundOrder order, int lineNumber, Item item, int orderedQuantity, String requestedLot) {
        this.id = id;
        this.order = order;
        this.lineNumber = lineNumber;
        this.item = item;
        this.orderedQuantity = orderedQuantity;
        this.requestedLot = requestedLot;
        this.status = LineStatus.NEW;
    }

    public int remaining() {
        return orderedQuantity - allocatedQuantity;
    }

    /** Records more allocated stock and sets the line's status after an allocation run. */
    void allocated(int quantity) {
        allocatedQuantity += quantity;
        refreshStatus();
    }

    /** Records stock picked into an outbound container. */
    void picked(int quantity) {
        pickedQuantity += quantity;
        refreshStatus();
    }

    /** Reserved stock turned out to be missing, so the line needs it again. */
    void releaseAllocated(int quantity) {
        allocatedQuantity -= quantity;
        refreshStatus();
    }

    private void refreshStatus() {
        if (pickedQuantity >= orderedQuantity) {
            status = LineStatus.PICKED;
        } else if (allocatedQuantity >= orderedQuantity) {
            status = LineStatus.ALLOCATED;
        } else if (allocatedQuantity > 0) {
            status = LineStatus.PARTIALLY_ALLOCATED;
        } else {
            status = LineStatus.BACKORDERED;
        }
    }

    void cancel() {
        allocatedQuantity = 0;
        status = LineStatus.CANCELLED;
    }

    public UUID getId() { return id; }
    public OutboundOrder getOrder() { return order; }
    public int getLineNumber() { return lineNumber; }
    public Item getItem() { return item; }
    public int getOrderedQuantity() { return orderedQuantity; }
    public int getPickedQuantity() { return pickedQuantity; }
    public int getAllocatedQuantity() { return allocatedQuantity; }
    public String getRequestedLot() { return requestedLot; }
    public LineStatus getStatus() { return status; }
}
