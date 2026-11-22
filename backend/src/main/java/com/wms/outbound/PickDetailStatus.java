package com.wms.outbound;

/** Where one pick of a task is. */
public enum PickDetailStatus {
    /** Still to pick, or partly picked. */
    OPEN,
    /** Fully picked into the outbound container. */
    PICKED,
    /** Closed with less than was directed; what was missing is on hold to be counted. */
    SHORT,
    /** No longer needed, e.g. the order was cancelled. */
    CANCELLED
}
