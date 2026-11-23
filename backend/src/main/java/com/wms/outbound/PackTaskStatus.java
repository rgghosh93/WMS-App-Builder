package com.wms.outbound;

public enum PackTaskStatus {
    /** The container is at the pack station, waiting for a packer. */
    OPEN,
    /** A packer is working on it. */
    IN_PROGRESS,
    /** Everything in the container is packed. */
    COMPLETED
}
