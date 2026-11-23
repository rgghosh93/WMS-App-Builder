package com.wms.inbound;

import java.time.LocalDate;
import java.util.UUID;

import com.wms.facility.FacilityRecord;
import com.wms.masterdata.item.Item;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** One expected item on an ASN, in eaches, and how much of it has come. */
@Entity
@Table(name = "asn_lines")
public class AsnLine extends FacilityRecord {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "asn_id", nullable = false)
    private Asn asn;

    @Column(name = "line_number", nullable = false)
    private int lineNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "item_id", nullable = false)
    private Item item;

    @Column(name = "expected_quantity", nullable = false)
    private int expectedQuantity;

    @Column(name = "received_quantity", nullable = false)
    private int receivedQuantity;

    @Column(name = "lot_number", length = 40)
    private String lotNumber;

    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    /** The LPN the vendor packed this in, if they said. */
    @Column(name = "lpn_id", length = 40)
    private String lpnId;

    protected AsnLine() {
        // for JPA
    }

    AsnLine(UUID id, Asn asn, int lineNumber, Item item, int expectedQuantity, String lotNumber, LocalDate expiryDate, String lpnId) {
        this.id = id;
        this.asn = asn;
        this.lineNumber = lineNumber;
        this.item = item;
        this.expectedQuantity = expectedQuantity;
        this.lotNumber = lotNumber;
        this.expiryDate = expiryDate;
        this.lpnId = lpnId;
    }

    /** Eaches still to come; none if more came than expected. */
    public int remaining() {
        return Math.max(0, expectedQuantity - receivedQuantity);
    }

    void receive(int quantity) {
        receivedQuantity += quantity;
    }

    void reverse(int quantity) {
        receivedQuantity -= quantity;
    }

    public UUID getId() { return id; }
    public Asn getAsn() { return asn; }
    public int getLineNumber() { return lineNumber; }
    public Item getItem() { return item; }
    public int getExpectedQuantity() { return expectedQuantity; }
    public int getReceivedQuantity() { return receivedQuantity; }
    public String getLotNumber() { return lotNumber; }
    public LocalDate getExpiryDate() { return expiryDate; }
    public String getLpnId() { return lpnId; }
}
