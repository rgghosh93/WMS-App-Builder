package com.wms.inventory;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Locations where nothing may be moved, because they are being counted. */
public interface LocationLocks {

    /** Those of these locations that are locked. */
    Set<UUID> locked(Collection<UUID> locationIds);

    default boolean isLocked(UUID locationId) {
        return !locked(List.of(locationId)).isEmpty();
    }
}
