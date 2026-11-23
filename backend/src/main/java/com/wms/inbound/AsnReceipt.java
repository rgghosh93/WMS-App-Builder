package com.wms.inbound;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.wms.facility.FacilityRecord;
import com.wms.inventory.InventoryStatus;
import com.wms.masterdata.container.Container;
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
import jakarta.persistence.Table;

/** Something received against an ASN line, into an LPN. Reversed receipts are kept for the record. */
@Entity
@Table(name = "asn_receipts")
public class AsnReceipt extends FacilityRecord {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "asn_id", nullable = false)
    private Asn asn;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "asn_line_id", nullable = false)
    private AsnLine line;

    /** Made by the device, so a repeated request isn't received twice. */
    @Column(name = "request_id", nullable = false)
    private UUID requestId;

    @Column(name = "request_part", nullable = false)
    private int requestPart;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "container_id", nullable = false)
    private Container container;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "location_id", nullable = false)
    private Location location;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "lot_number", length = 40)
    private String lotNumber;

    @Column(name = "serial_number", length = 60)
    private String serialNumber;

    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "inventory_status", nullable = false, length = 12)
    private InventoryStatus inventoryStatus;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "received_by", nullable = false)
    private AppUser receivedBy;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reversed_by")
    private AppUser reversedBy;

    @Column(name = "reversed_at")
    private Instant reversedAt;

    protected AsnReceipt() {
        // for JPA
    }

    AsnReceipt(AsnLine line, UUID requestId, int requestPart, Container container, Location location, int quantity,
               String lotNumber, String serialNumber, LocalDate expiryDate, InventoryStatus inventoryStatus, AppUser user) {
        this.id = UUID.randomUUID();
        this.asn = line.getAsn();
        this.line = line;
        this.requestId = requestId;
        this.requestPart = requestPart;
        this.container = container;
        this.location = location;
        this.quantity = quantity;
        this.lotNumber = lotNumber;
        this.serialNumber = serialNumber;
        this.expiryDate = expiryDate;
        this.inventoryStatus = inventoryStatus;
        this.receivedBy = user;
        this.receivedAt = Instant.now();
    }

    void reverse(AppUser user) {
        reversedBy = user;
        reversedAt = Instant.now();
    }

    boolean isReversed() {
        return reversedAt != null;
    }

    public UUID getId() { return id; }
    public Asn getAsn() { return asn; }
    public AsnLine getLine() { return line; }
    public Container getContainer() { return container; }
    public Location getLocation() { return location; }
    public int getQuantity() { return quantity; }
    public String getLotNumber() { return lotNumber; }
    public String getSerialNumber() { return serialNumber; }
    public LocalDate getExpiryDate() { return expiryDate; }
    public InventoryStatus getInventoryStatus() { return inventoryStatus; }
    public AppUser getReceivedBy() { return receivedBy; }
    public Instant getReceivedAt() { return receivedAt; }
    public AppUser getReversedBy() { return reversedBy; }
    public Instant getReversedAt() { return reversedAt; }
}
