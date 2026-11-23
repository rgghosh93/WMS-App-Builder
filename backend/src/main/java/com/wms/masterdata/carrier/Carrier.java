package com.wms.masterdata.carrier;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.wms.common.ActiveStatus;

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

/** A company that takes shipments away, and the services it offers. */
@Entity
@Table(name = "carriers")
public class Carrier {

    @Id
    private UUID id;

    @Column(name = "carrier_code", nullable = false, length = 20)
    private String carrierCode;

    @Column(nullable = false, length = 120)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private CarrierMode mode;

    /** Every carton needs a tracking number before it can be loaded. */
    @Column(name = "tracking_required", nullable = false)
    private boolean trackingRequired;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ActiveStatus status;

    @OneToMany(mappedBy = "carrier", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("serviceCode ASC")
    private List<CarrierService> services = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Carrier() {
        // for JPA
    }

    public Carrier(UUID id) {
        this.id = id;
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

    /** Marks the carrier changed, so a change to only its services is caught as a concurrent edit too. */
    void touch() {
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getCarrierCode() { return carrierCode; }
    public void setCarrierCode(String carrierCode) { this.carrierCode = carrierCode; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public CarrierMode getMode() { return mode; }
    public void setMode(CarrierMode mode) { this.mode = mode; }
    public boolean isTrackingRequired() { return trackingRequired; }
    public void setTrackingRequired(boolean trackingRequired) { this.trackingRequired = trackingRequired; }
    public ActiveStatus getStatus() { return status; }
    public void setStatus(ActiveStatus status) { this.status = status; }
    public List<CarrierService> getServices() { return services; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
}
