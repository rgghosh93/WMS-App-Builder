package com.wms.outbound;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** A customer order to ship. */
@Entity
@Table(name = "outbound_orders")
public class OutboundOrder {

    @Id
    private UUID id;

    @Column(name = "order_number", nullable = false, length = 40)
    private String orderNumber;

    @Column(name = "customer_name", nullable = false, length = 120)
    private String customerName;

    @Column(name = "requested_ship_date")
    private LocalDate requestedShipDate;

    /** 1 (most urgent) to 9. */
    @Column(nullable = false)
    private int priority;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private OrderStatus status;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNumber ASC")
    private List<OrderLine> lines = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected OutboundOrder() {
        // for JPA
    }

    public OutboundOrder(UUID id, String orderNumber) {
        this.id = id;
        this.orderNumber = orderNumber;
        this.status = OrderStatus.NEW;
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

    /** Sets the order's status from its lines. */
    void refreshStatus() {
        List<OrderLine> open = lines.stream().filter(l -> l.getStatus() != LineStatus.CANCELLED).toList();
        if (open.isEmpty()) {
            status = OrderStatus.CANCELLED;
        } else if (open.stream().allMatch(l -> l.getStatus() == LineStatus.NEW)) {
            status = OrderStatus.NEW;
        } else if (open.stream().allMatch(l -> l.getStatus() == LineStatus.ALLOCATED)) {
            status = OrderStatus.ALLOCATED;
        } else if (open.stream().allMatch(l -> l.getAllocatedQuantity() == 0)) {
            status = OrderStatus.BACKORDERED;
        } else {
            status = OrderStatus.PARTIALLY_ALLOCATED;
        }
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getOrderNumber() { return orderNumber; }
    public String getCustomerName() { return customerName; }
    public void setCustomerName(String customerName) { this.customerName = customerName; }
    public LocalDate getRequestedShipDate() { return requestedShipDate; }
    public void setRequestedShipDate(LocalDate requestedShipDate) { this.requestedShipDate = requestedShipDate; }
    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }
    public OrderStatus getStatus() { return status; }
    void setStatus(OrderStatus status) { this.status = status; }
    public List<OrderLine> getLines() { return lines; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
}
