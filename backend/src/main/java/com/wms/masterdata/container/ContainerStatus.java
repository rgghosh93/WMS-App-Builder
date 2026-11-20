package com.wms.masterdata.container;

/** Where a permanent container is in its life. */
public enum ContainerStatus {
    /** Empty and ready to use. */
    AVAILABLE,
    /** Holding inventory or assigned to work. */
    IN_USE,
    /** Damaged, being cleaned or otherwise not usable for now. */
    ON_HOLD,
    /** Taken out of service; kept for history. */
    RETIRED
}
