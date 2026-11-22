package com.wms.outbound;

public enum PickTaskStatus {
    /** Created and waiting for a picker. */
    OPEN,
    /** A picker is working on it. */
    IN_PROGRESS,
    /** Everything is picked; the container still has to be dropped at a pack station. */
    PICKED,
    /** Picked and dropped. */
    COMPLETED,
    /** No longer needed, e.g. the order was cancelled. */
    CANCELLED
}
