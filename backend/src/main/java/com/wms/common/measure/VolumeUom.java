package com.wms.common.measure;

/** Unit of measure for volume. */
public enum VolumeUom {
    CU_IN(1),
    CU_FT(1728),
    CU_CM(1 / 16.387064),
    CU_M(1_000_000 / 16.387064),
    L(1000 / 16.387064);

    private final double cubicInches;

    VolumeUom(double cubicInches) {
        this.cubicInches = cubicInches;
    }

    /** A volume in this unit, in cubic inches. */
    public double toCubicInches(double volume) {
        return volume * cubicInches;
    }
}
