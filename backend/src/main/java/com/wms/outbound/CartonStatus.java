package com.wms.outbound;

/** Where a shipping carton is in packing. */
public enum CartonStatus {
    /** Being packed. */
    OPEN,
    /** Packed and sealed, ready to ship. */
    CLOSED,
    /** Its order was cancelled or undone. What is in it goes back to storage. */
    CANCELLED
}
