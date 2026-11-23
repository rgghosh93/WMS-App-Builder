package com.wms.outbound;

/** Where a shipping carton is in packing. */
public enum CartonStatus {
    /** Being packed. */
    OPEN,
    /** Packed and sealed, ready to ship. */
    CLOSED
}
