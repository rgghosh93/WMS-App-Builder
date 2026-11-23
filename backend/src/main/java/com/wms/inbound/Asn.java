package com.wms.inbound;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.wms.facility.FacilityRecord;
import com.wms.masterdata.item.Item;
import com.wms.masterdata.owner.Owner;
import com.wms.users.AppUser;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** An advance shipping notice: what a vendor says is coming. */
@Entity
@Table(name = "asns")
public class Asn extends FacilityRecord {

    @Id
    private UUID id;

    /** Whose it is. Set when it is made, and never changed. */
    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId = Owner.FIRST;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", insertable = false, updatable = false)
    private Owner owner;

    @Column(name = "asn_number", nullable = false, length = 40)
    private String asnNumber;

    @Column(name = "vendor_name", nullable = false, length = 120)
    private String vendorName;

    /** The vendor's or carrier's own number: a PO, a bill of lading, a trailer. */
    @Column(length = 60)
    private String reference;

    @Column(name = "expected_date")
    private LocalDate expectedDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private AsnStatus status;

    /** Nobody announced it: it was started on the dock, and its lines are made as it is received. */
    @Column(nullable = false, updatable = false)
    private boolean blind;

    @OneToMany(mappedBy = "asn", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNumber ASC")
    private List<AsnLine> lines = new ArrayList<>();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "closed_by")
    private AppUser closedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Version
    private long version;

    protected Asn() {
        // for JPA
    }

    Asn(UUID id, String asnNumber, String vendorName, String reference, LocalDate expectedDate) {
        this.id = id;
        this.asnNumber = asnNumber;
        this.vendorName = vendorName;
        this.reference = reference;
        this.expectedDate = expectedDate;
        this.status = AsnStatus.EXPECTED;
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

    /** A receipt of what nobody announced. */
    static Asn blind(UUID id, String number, String vendorName, String reference) {
        Asn asn = new Asn(id, number, vendorName, reference, null);
        asn.blind = true;
        asn.status = AsnStatus.RECEIVING;
        return asn;
    }

    /** The line for an item and lot, made when the first of it comes. Only for blind receipts. */
    AsnLine lineFor(Item item, String lot) {
        return lines.stream().filter(l -> l.getItem().getId().equals(item.getId()) && Objects.equals(l.getLotNumber(), lot)).findFirst()
                .orElseGet(() -> {
                    AsnLine line = new AsnLine(UUID.randomUUID(), this, lines.size() + 1, item, 0, lot, null, null);
                    lines.add(line);
                    return line;
                });
    }

    void startReceiving() {
        status = AsnStatus.RECEIVING;
    }

    void close(AppUser user) {
        status = AsnStatus.RECEIVED;
        closedBy = user;
        closedAt = Instant.now();
    }

    void cancel() {
        status = AsnStatus.CANCELLED;
        closedAt = Instant.now();
    }

    boolean isOpen() {
        return status == AsnStatus.EXPECTED || status == AsnStatus.RECEIVING;
    }

    boolean anyReceived() {
        return lines.stream().anyMatch(l -> l.getReceivedQuantity() > 0);
    }

    public Owner getOwner() { return owner; }
    public UUID getOwnerId() { return ownerId; }

    public void setOwner(Owner owner) {
        this.owner = owner;
        this.ownerId = owner.getId();
    }

    public UUID getId() { return id; }
    public String getAsnNumber() { return asnNumber; }
    public String getVendorName() { return vendorName; }
    public String getReference() { return reference; }
    public LocalDate getExpectedDate() { return expectedDate; }
    public boolean isBlind() { return blind; }
    public AsnStatus getStatus() { return status; }
    public List<AsnLine> getLines() { return lines; }
    public AppUser getClosedBy() { return closedBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getClosedAt() { return closedAt; }
}
