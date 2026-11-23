package com.wms.common.measure;

/** Unit of measure for weight. */
public enum WeightUom {
    LB(1),
    OZ(1 / 16.0),
    KG(2.2046226218),
    G(0.0022046226218);

    private final double pounds;

    WeightUom(double pounds) {
        this.pounds = pounds;
    }

    /** A weight in this unit, in pounds. */
    public double toPounds(double weight) {
        return weight * pounds;
    }
}
