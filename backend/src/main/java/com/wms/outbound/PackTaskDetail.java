package com.wms.outbound;

import java.util.UUID;

import com.wms.masterdata.item.Item;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** Stock to pack for one order line: what one pick put in the container. */
@Entity
@Table(name = "pack_task_details")
public class PackTaskDetail {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pack_task_id", nullable = false)
    private PackTask task;

    @Column(name = "detail_number", nullable = false)
    private int detailNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private OutboundOrder order;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_line_id", nullable = false)
    private OrderLine line;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "item_id", nullable = false)
    private Item item;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pick_task_detail_id", nullable = false)
    private PickTaskDetail pickDetail;

    /** The reservation of what is still in the container; null once all of it is packed or missing. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "allocation_id")
    private Allocation allocation;

    @Column(name = "lot_number", length = 40)
    private String lotNumber;

    @Column(name = "serial_number", length = 60)
    private String serialNumber;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "packed_quantity", nullable = false)
    private int packedQuantity;

    @Column(name = "short_quantity", nullable = false)
    private int shortQuantity;

    @Version
    private long version;

    protected PackTaskDetail() {
        // for JPA
    }

    PackTaskDetail(UUID id, PackTask task, int detailNumber, PickTaskDetail pick) {
        this.id = id;
        this.task = task;
        this.detailNumber = detailNumber;
        this.order = pick.getOrder();
        this.line = pick.getLine();
        this.item = pick.getItem();
        this.pickDetail = pick;
        this.allocation = pick.getPickedAllocation();
        this.lotNumber = pick.getLotNumber();
        this.serialNumber = pick.getSerialNumber();
        this.quantity = pick.getPickedQuantity();
    }

    public int remaining() {
        return quantity - packedQuantity - shortQuantity;
    }

    void packed(int eaches) {
        packedQuantity += eaches;
    }

    void unpacked(int eaches) {
        packedQuantity -= eaches;
    }

    void missing(int eaches) {
        shortQuantity += eaches;
    }

    void setAllocation(Allocation allocation) {
        this.allocation = allocation;
    }

    public UUID getId() { return id; }
    public PackTask getTask() { return task; }
    public int getDetailNumber() { return detailNumber; }
    public OutboundOrder getOrder() { return order; }
    public OrderLine getLine() { return line; }
    public Item getItem() { return item; }
    public PickTaskDetail getPickDetail() { return pickDetail; }
    public Allocation getAllocation() { return allocation; }
    public String getLotNumber() { return lotNumber; }
    public String getSerialNumber() { return serialNumber; }
    public int getQuantity() { return quantity; }
    public int getPackedQuantity() { return packedQuantity; }
    public int getShortQuantity() { return shortQuantity; }
}
