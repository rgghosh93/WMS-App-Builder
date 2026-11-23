package com.wms.outbound;

public enum ShipmentStatus {
    /** Cartons are packed; none are loaded. */
    OPEN,
    /** Some cartons are on a truck. */
    LOADING,
    /** Gone. */
    SHIPPED
}
