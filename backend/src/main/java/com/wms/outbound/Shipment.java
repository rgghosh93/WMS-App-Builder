package com.wms.outbound;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.wms.facility.FacilityRecord;
import com.wms.masterdata.carrier.CarrierService;

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

/** Closed cartons of one order going out together. Created when the order's first carton is closed. */
@Entity
@Table(name = "shipments")
public class Shipment extends FacilityRecord {

    @Id
    private UUID id;

    @Column(name = "shipment_number", nullable = false, length = 20)
    private String shipmentNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private OutboundOrder order;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ShipmentStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "carrier_service_id")
    private CarrierService carrierService;

    /** The manifest it is being loaded on, or went out on. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "manifest_id")
    private Manifest manifest;

    @OneToMany(mappedBy = "shipment")
    @OrderBy("closedAt ASC")
    private List<PackCarton> cartons = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "shipped_at")
    private Instant shippedAt;

    @Version
    private long version;

    protected Shipment() {
        // for JPA
    }

    Shipment(UUID id, String shipmentNumber, OutboundOrder order) {
        this.id = id;
        this.shipmentNumber = shipmentNumber;
        this.order = order;
        this.status = ShipmentStatus.OPEN;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    /** Marks the shipment changed, so two people changing it at once can't both succeed. */
    void touch() {
        updatedAt = Instant.now();
    }

    void add(PackCarton carton) {
        cartons.add(carton);
        carton.setShipment(this);
        touch();
    }

    void remove(PackCarton carton) {
        cartons.remove(carton);
        touch();
    }

    void setCarrierService(CarrierService carrierService) {
        this.carrierService = carrierService;
        touch();
    }

    /** A carton went on a truck. */
    void loadedOn(Manifest manifest) {
        this.manifest = manifest;
        status = ShipmentStatus.LOADING;
        touch();
    }

    /** A carton came off a truck. */
    void unloaded() {
        if (cartons.stream().noneMatch(PackCarton::isLoaded)) {
            manifest = null;
            status = ShipmentStatus.OPEN;
        }
        touch();
    }

    void ship(Instant at) {
        status = ShipmentStatus.SHIPPED;
        shippedAt = at;
        touch();
    }

    /** Everything that still has to be filled in before its cartons can be loaded. */
    List<String> missing() {
        List<String> missing = new ArrayList<>();
        if (order.getShipTo() == null) {
            missing.add("Enter the ship-to address");
        }
        if (carrierService == null) {
            missing.add("Choose a carrier service");
        } else if (carrierService.getCarrier().isTrackingRequired()) {
            long without = cartons.stream().filter(c -> c.getTrackingNumber() == null).count();
            if (without > 0) {
                missing.add("Enter tracking numbers (%d carton%s)".formatted(without, without == 1 ? "" : "s"));
            }
        }
        return missing;
    }

    public UUID getId() { return id; }
    public String getShipmentNumber() { return shipmentNumber; }
    public OutboundOrder getOrder() { return order; }
    public ShipmentStatus getStatus() { return status; }
    public CarrierService getCarrierService() { return carrierService; }
    public Manifest getManifest() { return manifest; }
    public List<PackCarton> getCartons() { return cartons; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getShippedAt() { return shippedAt; }
    public long getVersion() { return version; }
}
