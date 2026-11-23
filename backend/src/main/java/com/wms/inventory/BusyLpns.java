package com.wms.inventory;

import java.util.Optional;
import java.util.UUID;

/** Containers that work is going on with, so they can't be moved away or let go of (provided by the outbound flow). */
public interface BusyLpns {

    /** What is going on with a container, or empty if nothing. */
    Optional<String> busyWith(UUID containerId);
}
