package com.wms.masterdata.container;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.wms.common.ActiveStatus;
import com.wms.common.measure.DimensionUom;
import com.wms.common.measure.VolumeUom;
import com.wms.common.measure.WeightUom;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** A kind of container that can hold inventory, such as a standard pallet or a medium tote. */
@Entity
@Table(name = "container_types")
public class ContainerType {

    @Id
    private UUID id;

    @Column(name = "container_type_id", nullable = false, length = 40)
    private String containerTypeId;

    @Column(nullable = false, length = 200)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ContainerCategory category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ActiveStatus status;

    @Column(nullable = false)
    private boolean reusable;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal width;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal depth;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal height;

    @Enumerated(EnumType.STRING)
    @Column(name = "dimension_uom", nullable = false, length = 4)
    private DimensionUom dimensionUom;

    @Column(name = "tare_weight", precision = 12, scale = 3)
    private BigDecimal tareWeight;

    @Enumerated(EnumType.STRING)
    @Column(name = "tare_weight_uom", length = 4)
    private WeightUom tareWeightUom;

    @Column(name = "max_weight", precision = 12, scale = 3)
    private BigDecimal maxWeight;

    @Enumerated(EnumType.STRING)
    @Column(name = "max_weight_uom", length = 4)
    private WeightUom maxWeightUom;

    @Column(name = "max_volume", precision = 14, scale = 3)
    private BigDecimal maxVolume;

    @Enumerated(EnumType.STRING)
    @Column(name = "max_volume_uom", length = 6)
    private VolumeUom maxVolumeUom;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected ContainerType() {
        // for JPA
    }

    public ContainerType(UUID id) {
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

    public UUID getId() { return id; }
    public String getContainerTypeId() { return containerTypeId; }
    public void setContainerTypeId(String containerTypeId) { this.containerTypeId = containerTypeId; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public ContainerCategory getCategory() { return category; }
    public void setCategory(ContainerCategory category) { this.category = category; }
    public ActiveStatus getStatus() { return status; }
    public void setStatus(ActiveStatus status) { this.status = status; }
    public boolean isReusable() { return reusable; }
    public void setReusable(boolean reusable) { this.reusable = reusable; }
    public BigDecimal getWidth() { return width; }
    public void setWidth(BigDecimal width) { this.width = width; }
    public BigDecimal getDepth() { return depth; }
    public void setDepth(BigDecimal depth) { this.depth = depth; }
    public BigDecimal getHeight() { return height; }
    public void setHeight(BigDecimal height) { this.height = height; }
    public DimensionUom getDimensionUom() { return dimensionUom; }
    public void setDimensionUom(DimensionUom dimensionUom) { this.dimensionUom = dimensionUom; }
    public BigDecimal getTareWeight() { return tareWeight; }
    public void setTareWeight(BigDecimal tareWeight) { this.tareWeight = tareWeight; }
    public WeightUom getTareWeightUom() { return tareWeightUom; }
    public void setTareWeightUom(WeightUom tareWeightUom) { this.tareWeightUom = tareWeightUom; }
    public BigDecimal getMaxWeight() { return maxWeight; }
    public void setMaxWeight(BigDecimal maxWeight) { this.maxWeight = maxWeight; }
    public WeightUom getMaxWeightUom() { return maxWeightUom; }
    public void setMaxWeightUom(WeightUom maxWeightUom) { this.maxWeightUom = maxWeightUom; }
    public BigDecimal getMaxVolume() { return maxVolume; }
    public void setMaxVolume(BigDecimal maxVolume) { this.maxVolume = maxVolume; }
    public VolumeUom getMaxVolumeUom() { return maxVolumeUom; }
    public void setMaxVolumeUom(VolumeUom maxVolumeUom) { this.maxVolumeUom = maxVolumeUom; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
}
