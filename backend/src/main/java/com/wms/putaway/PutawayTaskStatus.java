package com.wms.putaway;

public enum PutawayTaskStatus {
    /** The LPN waits at receiving. */
    OPEN,
    /** Someone has it. */
    IN_PROGRESS,
    /** It is in storage. */
    COMPLETED,
    /** Nothing was left to put away. */
    CANCELLED
}
