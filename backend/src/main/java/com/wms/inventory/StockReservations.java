package com.wms.inventory;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Stock reserved for orders, as far as changing it goes (provided by the outbound flow). */
public interface StockReservations {

    /** @param orderIds orders that lost reserved stock */
    record Released(Set<UUID> orderIds, int quantity) {
    }

    /**
     * Lets go of reservations nobody has started picking.
     *
     * @param keep how much may stay reserved of each inventory record
     * @param why  for the orders' activity
     */
    Released release(Map<UUID, Integer> keep, String why);

    /** Reserves other stock for orders that lost some. */
    void reallocate(Released released);

    /** Why a location can't be counted now, if someone is picking there. */
    Optional<String> beingPicked(UUID locationId);
}
