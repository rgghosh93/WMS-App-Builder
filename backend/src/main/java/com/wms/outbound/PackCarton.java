package com.wms.outbound;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.wms.common.measure.WeightUom;
import com.wms.masterdata.container.Container;
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
import jakarta.persistence.Table;

/** A shipping carton packed for one order, and what is in it. */
@Entity
@Table(name = "pack_cartons")
public class PackCarton {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pack_task_id", nullable = false)
    private PackTask task;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private OutboundOrder order;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "container_id", nullable = false)
    private Container container;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private CartonStatus status;

    @Column(precision = 12, scale = 3)
    private BigDecimal weight;

    @Enumerated(EnumType.STRING)
    @Column(name = "weight_uom", length = 4)
    private WeightUom weightUom;

    @OneToMany(mappedBy = "carton", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PackCartonLine> lines = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "closed_by")
    private AppUser closedBy;

    /** The shipment it goes out on, once closed. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shipment_id")
    private Shipment shipment;

    @Column(name = "tracking_number", length = 60)
    private String trackingNumber;

    @Column(name = "loaded_at")
    private Instant loadedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "loaded_by")
    private AppUser loadedBy;

    protected PackCarton() {
        // for JPA
    }

    PackCarton(UUID id, PackTask task, OutboundOrder order, Container container) {
        this.id = id;
        this.task = task;
        this.order = order;
        this.container = container;
        this.status = CartonStatus.OPEN;
        this.createdAt = Instant.now();
    }

    Optional<PackCartonLine> lineFor(PackTaskDetail detail) {
        return lines.stream().filter(l -> l.getDetail().getId().equals(detail.getId())).findFirst();
    }

    void addLine(PackTaskDetail detail, Allocation allocation, int quantity) {
        lines.add(new PackCartonLine(UUID.randomUUID(), this, detail, allocation, quantity));
    }

    void close(BigDecimal weight, WeightUom weightUom, AppUser user) {
        this.weight = weight;
        this.weightUom = weight == null ? null : weightUom;
        this.status = CartonStatus.CLOSED;
        this.closedAt = Instant.now();
        this.closedBy = user;
    }

    boolean isLoaded() {
        return loadedAt != null;
    }

    void load(AppUser user) {
        loadedAt = Instant.now();
        loadedBy = user;
    }

    void unload() {
        loadedAt = null;
        loadedBy = null;
    }

    void setShipment(Shipment shipment) {
        this.shipment = shipment;
    }

    void setTrackingNumber(String trackingNumber) {
        this.trackingNumber = trackingNumber;
    }

    public int totalQuantity() {
        return lines.stream().mapToInt(PackCartonLine::getQuantity).sum();
    }

    public UUID getId() { return id; }
    public Shipment getShipment() { return shipment; }
    public String getTrackingNumber() { return trackingNumber; }
    public Instant getLoadedAt() { return loadedAt; }
    public PackTask getTask() { return task; }
    public OutboundOrder getOrder() { return order; }
    public Container getContainer() { return container; }
    public CartonStatus getStatus() { return status; }
    public BigDecimal getWeight() { return weight; }
    public WeightUom getWeightUom() { return weightUom; }
    public List<PackCartonLine> getLines() { return lines; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getClosedAt() { return closedAt; }
    public AppUser getClosedBy() { return closedBy; }
}
