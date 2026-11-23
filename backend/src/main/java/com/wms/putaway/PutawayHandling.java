package com.wms.putaway;

/** What happens to the LPN. */
public enum PutawayHandling {
    /** The LPN is stored as it is. */
    KEEP_LPN,
    /** The stock is put loose in the location, and the LPN is done with. */
    EMPTY_LPN
}
