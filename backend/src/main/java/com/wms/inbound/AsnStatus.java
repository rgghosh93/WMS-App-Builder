package com.wms.inbound;

public enum AsnStatus {
    /** Announced; nothing has arrived. */
    EXPECTED,
    /** Some of it has been received. */
    RECEIVING,
    /** Closed: what came is what came. */
    RECEIVED,
    /** Not coming. */
    CANCELLED
}
