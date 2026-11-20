package com.wms.masterdata.location;

import java.util.Optional;
import java.util.UUID;

/**
 * Something that refers to locations (containers today; inventory and tasks
 * later). A location can't be deleted while any usage reports it.
 */
public interface LocationUsage {

    /** A short reason the location is in use, e.g. "3 containers are at this location", or empty. */
    Optional<String> describeUse(UUID locationId);
}
