package com.wms.putaway;

/** How a rule chooses a location. "Nearest" is first in walking order, which is location ID order. */
public enum PutawayStrategy {
    /** One given location. */
    FIXED,
    /** In one put-away zone: with the same stock if there is room, else an empty location, else anywhere with room. */
    ZONE,
    /** The nearest empty storage location, anywhere or in one put-away zone. */
    NEAREST_EMPTY
}
