package com.wms.inventory;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/** Stock set aside to be moved within the warehouse (provided by replenishment). */
public interface PlannedMoves extends AllocationLookup {

    /** Calls off moves of this stock nobody has started. Moves under way stay. */
    void callOff(Collection<UUID> inventoryIds, String why);

    /** Why a location can't be counted now, if someone is moving stock from or to it. */
    Optional<String> beingMoved(UUID locationId);
}
