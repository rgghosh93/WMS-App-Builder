package com.wms.outbound;

public enum ManifestStatus {
    /** Being loaded at a dock door. */
    OPEN,
    /** Closed: everything on it has shipped. */
    CLOSED
}
