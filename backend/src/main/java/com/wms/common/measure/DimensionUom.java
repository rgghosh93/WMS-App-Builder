package com.wms.common.measure;

/** Unit of measure for length (width, depth, height). */
public enum DimensionUom {
    IN(1),
    FT(12),
    CM(1 / 2.54),
    M(100 / 2.54);

    private final double inches;

    DimensionUom(double inches) {
        this.inches = inches;
    }

    /** A length in this unit, in inches. */
    public double toInches(double length) {
        return length * inches;
    }
}
