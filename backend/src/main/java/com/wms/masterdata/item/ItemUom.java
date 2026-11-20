package com.wms.masterdata.item;

import java.math.BigDecimal;
import java.util.UUID;

import com.wms.common.measure.DimensionUom;
import com.wms.common.measure.WeightUom;
import com.wms.masterdata.uom.UnitOfMeasure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * One packaging level of an item, e.g. "1 CS = 12 EA", with its own barcode,
 * size and weight. Quantities are always in the base unit (eaches).
 */
@Entity
@Table(name = "item_uoms")
public class ItemUom {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "item_id", nullable = false)
    private Item item;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "uom_id", nullable = false)
    private UnitOfMeasure uom;

    @Column(nullable = false)
    private int quantity;

    @Column(length = 50)
    private String barcode;

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

    protected ItemUom() {
        // for JPA
    }

    public ItemUom(UUID id, Item item, UnitOfMeasure uom) {
        this.id = id;
        this.item = item;
        this.uom = uom;
    }

    public UUID getId() { return id; }
    public Item getItem() { return item; }
    public UnitOfMeasure getUom() { return uom; }
    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }
    public String getBarcode() { return barcode; }
    public void setBarcode(String barcode) { this.barcode = barcode; }
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
}
