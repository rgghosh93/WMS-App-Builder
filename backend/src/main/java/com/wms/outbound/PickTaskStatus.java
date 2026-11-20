package com.wms.outbound;

public enum PickTaskStatus {
    /** Created and waiting for a picker. */
    OPEN,
    /** No longer needed, e.g. the order was cancelled. */
    CANCELLED
}
