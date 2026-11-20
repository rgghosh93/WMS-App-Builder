package com.wms.common;

/** Whether a master-data record can be used for new work. */
public enum ActiveStatus {
    /** In use. */
    ACTIVE,
    /** Kept for history; not used for new work. */
    INACTIVE
}
