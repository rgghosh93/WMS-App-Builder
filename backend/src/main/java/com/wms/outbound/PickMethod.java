package com.wms.outbound;

/** How a pick task is worked. */
public enum PickMethod {
    /** Picks for a single order. */
    DISCRETE,
    /** Picks for several orders in one walk; the picker sorts each pick to its order. */
    BATCH
}
