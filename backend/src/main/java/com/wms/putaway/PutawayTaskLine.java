package com.wms.putaway;

import java.time.LocalDate;
import java.util.UUID;

import com.wms.inventory.InventoryRecord;
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

/** Something that was put away, kept after the stock has moved on. */
@Entity
@Table(name = "putaway_task_lines")
public class PutawayTaskLine {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_id", nullable = false)
    private PutawayTask task;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "item_id", nullable = false)
    private Item item;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "lot_number", length = 40)
    private String lotNumber;

    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "stock_status", nullable = false, length = 12)
    private InventoryStatus stockStatus;

    protected PutawayTaskLine() {
        // for JPA
    }

    PutawayTaskLine(PutawayTask task, InventoryRecord record) {
        this.id = UUID.randomUUID();
        this.task = task;
        this.item = record.getItem();
        this.quantity = record.getQuantity();
        this.lotNumber = record.getLotNumber();
        this.expiryDate = record.getExpiryDate();
        this.stockStatus = record.getStatus();
    }

    public Item getItem() { return item; }
    public int getQuantity() { return quantity; }
    public String getLotNumber() { return lotNumber; }
    public LocalDate getExpiryDate() { return expiryDate; }
    public InventoryStatus getStockStatus() { return stockStatus; }
}
