package com.wms.control;

import java.time.Instant;
import java.util.UUID;

import com.wms.masterdata.container.Container;
import com.wms.masterdata.item.Item;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** Something counted. */
@Entity
@Table(name = "count_entries")
public class CountEntry {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_id", nullable = false)
    private CountTask task;

    @Column(name = "request_id", nullable = false)
    private UUID requestId;

    @Column(name = "request_part", nullable = false)
    private int requestPart;

    /** The LPN it is in; null if loose. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "container_id")
    private Container container;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "item_id", nullable = false)
    private Item item;

    @Column(name = "lot_number", length = 40)
    private String lotNumber;

    @Column(name = "serial_number", length = 60)
    private String serialNumber;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "counted_at", nullable = false)
    private Instant countedAt;

    protected CountEntry() {
        // for JPA
    }

    CountEntry(CountTask task, UUID requestId, int requestPart, Container container, Item item, String lotNumber, String serialNumber,
               int quantity) {
        this.id = UUID.randomUUID();
        this.task = task;
        this.requestId = requestId;
        this.requestPart = requestPart;
        this.container = container;
        this.item = item;
        this.lotNumber = lotNumber;
        this.serialNumber = serialNumber;
        this.quantity = quantity;
        this.countedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getRequestId() { return requestId; }
    public Container getContainer() { return container; }
    public Item getItem() { return item; }
    public String getLotNumber() { return lotNumber; }
    public String getSerialNumber() { return serialNumber; }
    public int getQuantity() { return quantity; }
}
