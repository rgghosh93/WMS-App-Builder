package com.wms.outbound;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** One entry in an order's activity log. */
@Entity
@Table(name = "order_events")
public class OrderEvent {

    public enum Type { RECEIVED, ALLOCATED, SHORT, TASKS_CREATED, CANCELLED }

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private OutboundOrder order;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "event_type", nullable = false, length = 24)
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    private Type type;

    @Column(nullable = false, length = 500)
    private String message;

    protected OrderEvent() {
        // for JPA
    }

    OrderEvent(OutboundOrder order, Type type, String message) {
        this.id = UUID.randomUUID();
        this.order = order;
        this.type = type;
        this.message = message.length() > 500 ? message.substring(0, 497) + "..." : message;
        this.occurredAt = Instant.now();
    }

    public UUID getId() { return id; }
    public OutboundOrder getOrder() { return order; }
    public Instant getOccurredAt() { return occurredAt; }
    public Type getType() { return type; }
    public String getMessage() { return message; }
}
