package com.wms.outbound;

public enum LineStatus {
    NEW,
    PARTIALLY_ALLOCATED,
    ALLOCATED,
    /** All of it is picked. */
    PICKED,
    /** All of it is packed. */
    PACKED,
    /** All of it has shipped. */
    SHIPPED,
    BACKORDERED,
    CANCELLED
}
