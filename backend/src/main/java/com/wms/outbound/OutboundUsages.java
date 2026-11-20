package com.wms.outbound;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.wms.inventory.AllocationLookup;
import com.wms.masterdata.item.ItemUsage;

/** How the outbound flow shows up elsewhere: blocking item deletes, and allocated stock in inventory views. */
final class OutboundUsages {

    private OutboundUsages() {
    }

    /** An item on open order lines can't be deleted. */
    @Component
    static class OnOrders implements ItemUsage {
        private final OrderLineRepository lines;

        OnOrders(OrderLineRepository lines) {
            this.lines = lines;
        }

        @Override
        public Optional<String> describeUse(UUID itemId) {
            long count = lines.countOpenByItem(itemId);
            return count == 0 ? Optional.empty()
                    : Optional.of(count == 1 ? "it is on 1 open order line" : "it is on " + count + " open order lines");
        }
    }

    /** Lets the inventory views show how much of each record is reserved for orders. */
    @Component
    static class Reserved implements AllocationLookup {
        private final AllocationRepository allocations;

        Reserved(AllocationRepository allocations) {
            this.allocations = allocations;
        }

        @Override
        public Map<UUID, Integer> allocatedByInventory(Collection<UUID> inventoryIds) {
            Map<UUID, Integer> result = new HashMap<>();
            if (inventoryIds.isEmpty()) {
                return result;
            }
            for (Object[] row : allocations.sumByInventory(inventoryIds)) {
                result.put((UUID) row[0], ((Number) row[1]).intValue());
            }
            return result;
        }
    }
}
