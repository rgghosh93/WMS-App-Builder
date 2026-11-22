package com.wms.outbound;

import java.time.Instant;
import java.util.UUID;

import com.wms.inventory.InventoryRecord;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** Stock reserved for an order line: a quantity taken from one inventory record. */
@Entity
@Table(name = "allocations")
public class Allocation {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_line_id", nullable = false)
    private OrderLine line;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "inventory_id", nullable = false)
    private InventoryRecord inventory;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Allocation() {
        // for JPA
    }

    Allocation(UUID id, OrderLine line, InventoryRecord inventory, int quantity) {
        this.id = id;
        this.line = line;
        this.inventory = inventory;
        this.quantity = quantity;
        this.createdAt = Instant.now();
    }

    /** Adds to (or, if negative, takes from) the reserved quantity. */
    void changeQuantity(int by) {
        quantity += by;
    }

    public UUID getId() { return id; }
    public OrderLine getLine() { return line; }
    public InventoryRecord getInventory() { return inventory; }
    public int getQuantity() { return quantity; }
    public Instant getCreatedAt() { return createdAt; }
}
