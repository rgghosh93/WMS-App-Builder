package com.wms.inbound;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
public class Asn {

    @Id
    private UUID id;

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

    public UUID getId() { return id; }
    public String getAsnNumber() { return asnNumber; }
    public String getVendorName() { return vendorName; }
    public String getReference() { return reference; }
    public LocalDate getExpectedDate() { return expectedDate; }
    public AsnStatus getStatus() { return status; }
    public List<AsnLine> getLines() { return lines; }
    public AppUser getClosedBy() { return closedBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getClosedAt() { return closedAt; }
}
