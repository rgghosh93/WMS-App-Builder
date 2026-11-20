package com.wms.masterdata.item;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.wms.common.ActiveStatus;
import com.wms.common.measure.DimensionUom;
import com.wms.common.measure.WeightUom;

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

/** A product (SKU) the warehouse stores and moves. Dimensions and weight are per each. */
@Entity
@Table(name = "items")
public class Item {

    @Id
    private UUID id;

    @Column(name = "item_id", nullable = false, length = 40)
    private String itemId;

    @Column(nullable = false, length = 200)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ActiveStatus status;

    @Column(length = 50)
    private String barcode;

    @Column(name = "lot_controlled", nullable = false)
    private boolean lotControlled;

    @Column(name = "serial_controlled", nullable = false)
    private boolean serialControlled;

    @Column(name = "expiry_controlled", nullable = false)
    private boolean expiryControlled;

    @Column(name = "shelf_life_days")
    private Integer shelfLifeDays;

    @Column(precision = 10, scale = 2)
    private BigDecimal width;

    @Column(precision = 10, scale = 2)
    private BigDecimal depth;

    @Column(precision = 10, scale = 2)
    private BigDecimal height;

    @Enumerated(EnumType.STRING)
    @Column(name = "dimension_uom", length = 4)
    private DimensionUom dimensionUom;

    @Column(precision = 12, scale = 3)
    private BigDecimal weight;

    @Enumerated(EnumType.STRING)
    @Column(name = "weight_uom", length = 4)
    private WeightUom weightUom;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    /** Packaging levels above the each, smallest first. */
    @OneToMany(mappedBy = "item", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("quantity ASC")
    private List<ItemUom> uoms = new ArrayList<>();

    protected Item() {
        // for JPA
    }

    public Item(UUID id) {
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

    /** Marks the item as changed, e.g. when only its packaging changed, so its version moves on. */
    void touch() {
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public List<ItemUom> getUoms() { return uoms; }
    public String getItemId() { return itemId; }
    public void setItemId(String itemId) { this.itemId = itemId; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public ActiveStatus getStatus() { return status; }
    public void setStatus(ActiveStatus status) { this.status = status; }
    public String getBarcode() { return barcode; }
    public void setBarcode(String barcode) { this.barcode = barcode; }
    public boolean isLotControlled() { return lotControlled; }
    public void setLotControlled(boolean lotControlled) { this.lotControlled = lotControlled; }
    public boolean isSerialControlled() { return serialControlled; }
    public void setSerialControlled(boolean serialControlled) { this.serialControlled = serialControlled; }
    public boolean isExpiryControlled() { return expiryControlled; }
    public void setExpiryControlled(boolean expiryControlled) { this.expiryControlled = expiryControlled; }
    public Integer getShelfLifeDays() { return shelfLifeDays; }
    public void setShelfLifeDays(Integer shelfLifeDays) { this.shelfLifeDays = shelfLifeDays; }
    public BigDecimal getWidth() { return width; }
    public void setWidth(BigDecimal width) { this.width = width; }
    public BigDecimal getDepth() { return depth; }
    public void setDepth(BigDecimal depth) { this.depth = depth; }
    public BigDecimal getHeight() { return height; }
    public void setHeight(BigDecimal height) { this.height = height; }
    public DimensionUom getDimensionUom() { return dimensionUom; }
    public void setDimensionUom(DimensionUom dimensionUom) { this.dimensionUom = dimensionUom; }
    public BigDecimal getWeight() { return weight; }
    public void setWeight(BigDecimal weight) { this.weight = weight; }
    public WeightUom getWeightUom() { return weightUom; }
    public void setWeightUom(WeightUom weightUom) { this.weightUom = weightUom; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
}
