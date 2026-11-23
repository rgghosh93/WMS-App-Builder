package com.wms.inventory;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/** How much of each inventory record is reserved, for orders or to be moved. Each kind of work provides its own. */
public interface AllocationLookup {

    /** Reserved eaches per inventory record id; records with nothing reserved may be left out. */
    Map<UUID, Integer> allocatedByInventory(Collection<UUID> inventoryIds);
}
