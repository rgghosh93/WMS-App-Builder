package com.wms.outbound;

public enum LineStatus {
    NEW,
    PARTIALLY_ALLOCATED,
    ALLOCATED,
    /** All of it is picked. */
    PICKED,
    BACKORDERED,
    CANCELLED
}
