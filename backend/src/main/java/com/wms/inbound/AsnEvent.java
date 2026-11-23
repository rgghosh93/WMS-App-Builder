package com.wms.inbound;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** One entry in an ASN's activity log. */
@Entity
@Table(name = "asn_events")
public class AsnEvent {

    public enum Type { CREATED, RECEIVED, REVERSED, CLOSED, CANCELLED }

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "asn_id", nullable = false)
    private Asn asn;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 24)
    private Type type;

    @Column(nullable = false, length = 500)
    private String message;

    protected AsnEvent() {
        // for JPA
    }

    AsnEvent(Asn asn, Type type, String message) {
        this.id = UUID.randomUUID();
        this.asn = asn;
        this.type = type;
        this.message = message.length() > 500 ? message.substring(0, 497) + "..." : message;
        this.occurredAt = Instant.now();
    }

    public Instant getOccurredAt() { return occurredAt; }
    public Type getType() { return type; }
    public String getMessage() { return message; }
}
