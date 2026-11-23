package com.wms.control;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.wms.inventory.LocationLocks;

/** Locations being counted, or counted and not settled yet. */
@Component
class CountLocks implements LocationLocks {

    private final CountTaskRepository counts;

    CountLocks(CountTaskRepository counts) {
        this.counts = counts;
    }

    @Override
    public Set<UUID> locked(Collection<UUID> locationIds) {
        return locationIds.isEmpty() ? Set.of() : counts.findLocations(locationIds, CountTask.LOCKING);
    }
}
