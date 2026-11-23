package com.wms.outbound;

public enum PackTaskStatus {
    /** The container is at the pack station, waiting for a packer. */
    OPEN,
    /** A packer is working on it. */
    IN_PROGRESS,
    /** Everything in the container is packed. */
    COMPLETED,
    /** Nothing in it is to be packed any more: its orders were cancelled or undone. */
    CANCELLED;

    boolean isOpen() {
        return this == OPEN || this == IN_PROGRESS;
    }
}
