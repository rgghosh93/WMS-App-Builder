package com.wms.outbound;

/** Where an order is. Set from its lines after every allocation, pick or cancellation. */
public enum OrderStatus {
    /** Received; allocation has not run yet. */
    NEW,
    /** Some, but not all, of the ordered quantity has stock reserved. */
    PARTIALLY_ALLOCATED,
    /** Every line is fully allocated and has pick tasks. */
    ALLOCATED,
    /** Picking has started; some of the order is in outbound containers. */
    PICKING,
    /** Everything ordered is picked. */
    PICKED,
    /** Allocation ran but found no stock for any line. */
    BACKORDERED,
    /** Cancelled; its allocations were released and its tasks cancelled. */
    CANCELLED
}
