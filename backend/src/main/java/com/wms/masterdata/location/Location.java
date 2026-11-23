package com.wms.masterdata.location;

import com.wms.common.measure.DimensionUom;
import com.wms.common.measure.VolumeUom;
import com.wms.common.measure.WeightUom;
import com.wms.facility.FacilityRecord;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** A place where inventory is stored and/or acted on. */
@Entity
@Table(name = "locations")
public class Location extends FacilityRecord {

    @Id
    private UUID id;

    @Column(name = "location_id", nullable = false, length = 40)
    private String locationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "location_type", nullable = false, length = 20)
    private LocationType type;

    @Column(name = "area_code", length = 20)
    private String area;

    @Column(name = "zone_code", length = 20)
    private String zone;

    @Column(name = "aisle_code", length = 20)
    private String aisle;

    @Column(name = "bay_code", length = 20)
    private String bay;

    @Column(name = "level_code", length = 20)
    private String level;

    @Column(name = "position_code", length = 20)
    private String position;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal width;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal depth;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal height;

    @Enumerated(EnumType.STRING)
    @Column(name = "dimension_uom", nullable = false, length = 4)
    private DimensionUom dimensionUom;

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

    @Column(name = "max_lpns")
    private Integer maxLpns;

    @Column(name = "max_skus")
    private Integer maxSkus;

    @Column(name = "max_units")
    private Integer maxUnits;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Location() {
        // for JPA
    }

    public Location(UUID id) {
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
    public String getLocationId() { return locationId; }
    public void setLocationId(String locationId) { this.locationId = locationId; }
    public LocationType getType() { return type; }
    public void setType(LocationType type) { this.type = type; }
    public String getArea() { return area; }
    public void setArea(String area) { this.area = area; }
    public String getZone() { return zone; }
    public void setZone(String zone) { this.zone = zone; }
    public String getAisle() { return aisle; }
    public void setAisle(String aisle) { this.aisle = aisle; }
    public String getBay() { return bay; }
    public void setBay(String bay) { this.bay = bay; }
    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }
    public String getPosition() { return position; }
    public void setPosition(String position) { this.position = position; }
    public BigDecimal getWidth() { return width; }
    public void setWidth(BigDecimal width) { this.width = width; }
    public BigDecimal getDepth() { return depth; }
    public void setDepth(BigDecimal depth) { this.depth = depth; }
    public BigDecimal getHeight() { return height; }
    public void setHeight(BigDecimal height) { this.height = height; }
    public DimensionUom getDimensionUom() { return dimensionUom; }
    public void setDimensionUom(DimensionUom dimensionUom) { this.dimensionUom = dimensionUom; }
    public BigDecimal getMaxWeight() { return maxWeight; }
    public void setMaxWeight(BigDecimal maxWeight) { this.maxWeight = maxWeight; }
    public WeightUom getMaxWeightUom() { return maxWeightUom; }
    public void setMaxWeightUom(WeightUom maxWeightUom) { this.maxWeightUom = maxWeightUom; }
    public BigDecimal getMaxVolume() { return maxVolume; }
    public void setMaxVolume(BigDecimal maxVolume) { this.maxVolume = maxVolume; }
    public VolumeUom getMaxVolumeUom() { return maxVolumeUom; }
    public void setMaxVolumeUom(VolumeUom maxVolumeUom) { this.maxVolumeUom = maxVolumeUom; }
    public Integer getMaxLpns() { return maxLpns; }
    public void setMaxLpns(Integer maxLpns) { this.maxLpns = maxLpns; }
    public Integer getMaxSkus() { return maxSkus; }
    public void setMaxSkus(Integer maxSkus) { this.maxSkus = maxSkus; }
    public Integer getMaxUnits() { return maxUnits; }
    public void setMaxUnits(Integer maxUnits) { this.maxUnits = maxUnits; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
}
