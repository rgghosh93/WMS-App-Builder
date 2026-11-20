package com.wms.inventory;

/** Whether on-hand stock can be used. */
public enum InventoryStatus {
    /** Can be allocated and picked. */
    AVAILABLE,
    /** Held, e.g. for a quality check or a recall; not allocatable. */
    ON_HOLD,
    /** Damaged; not allocatable. */
    DAMAGED
}
