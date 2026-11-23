package com.wms.outbound;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.wms.facility.FacilityRecord;
import com.wms.masterdata.carrier.Carrier;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** What leaves with one carrier from one dock door. Closing it ships everything loaded. */
@Entity
@Table(name = "manifests")
public class Manifest extends FacilityRecord {

    @Id
    private UUID id;

    @Column(name = "manifest_number", nullable = false, length = 20)
    private String manifestNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "carrier_id", nullable = false)
    private Carrier carrier;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "door_id", nullable = false)
    private Location door;

    /** The door while open, null once closed; unique, so a door has one open manifest at a time. */
    @Column(name = "open_door_id")
    private UUID openDoorId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ManifestStatus status;

    @Column(name = "trailer_number", length = 40)
    private String trailerNumber;

    @Column(name = "seal_number", length = 40)
    private String sealNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false)
    private AppUser createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "closed_by")
    private AppUser closedBy;

    @OneToMany(mappedBy = "manifest")
    @OrderBy("shipmentNumber ASC")
    private List<Shipment> shipments = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Version
    private long version;

    protected Manifest() {
        // for JPA
    }

    Manifest(UUID id, String manifestNumber, Carrier carrier, Location door, AppUser createdBy) {
        this.id = id;
        this.manifestNumber = manifestNumber;
        this.carrier = carrier;
        this.door = door;
        this.openDoorId = door.getId();
        this.createdBy = createdBy;
        this.status = ManifestStatus.OPEN;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    void close(String trailerNumber, String sealNumber, AppUser user, Instant at) {
        this.trailerNumber = trailerNumber;
        this.sealNumber = sealNumber;
        this.closedBy = user;
        this.closedAt = at;
        this.updatedAt = at;
        this.openDoorId = null;
        this.status = ManifestStatus.CLOSED;
    }

    public UUID getId() { return id; }
    public String getManifestNumber() { return manifestNumber; }
    public Carrier getCarrier() { return carrier; }
    public Location getDoor() { return door; }
    public ManifestStatus getStatus() { return status; }
    public String getTrailerNumber() { return trailerNumber; }
    public String getSealNumber() { return sealNumber; }
    public AppUser getCreatedBy() { return createdBy; }
    public AppUser getClosedBy() { return closedBy; }
    public List<Shipment> getShipments() { return shipments; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getClosedAt() { return closedAt; }
}
