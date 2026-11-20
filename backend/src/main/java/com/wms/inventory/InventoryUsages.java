package com.wms.inventory;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.wms.masterdata.container.ContainerUsage;
import com.wms.masterdata.item.ItemUsage;
import com.wms.masterdata.location.LocationUsage;

/** Stops locations, containers and items that hold stock from being deleted. */
final class InventoryUsages {

    private InventoryUsages() {
    }

    @Component
    static class AtLocation implements LocationUsage {
        private final InventoryRepository repository;

        AtLocation(InventoryRepository repository) {
            this.repository = repository;
        }

        @Override
        public Optional<String> describeUse(UUID locationId) {
            long count = repository.countByLocation_Id(locationId);
            return count == 0 ? Optional.empty()
                    : Optional.of(count == 1 ? "1 inventory record is in it" : count + " inventory records are in it");
        }
    }

    @Component
    static class InContainer implements ContainerUsage {
        private final InventoryRepository repository;

        InContainer(InventoryRepository repository) {
            this.repository = repository;
        }

        @Override
        public Optional<String> describeUse(UUID containerId) {
            long count = repository.countByContainer_Id(containerId);
            return count == 0 ? Optional.empty()
                    : Optional.of(count == 1 ? "it holds 1 inventory record" : "it holds " + count + " inventory records");
        }
    }

    @Component
    static class OfItem implements ItemUsage {
        private final InventoryRepository repository;

        OfItem(InventoryRepository repository) {
            this.repository = repository;
        }

        @Override
        public Optional<String> describeUse(UUID itemId) {
            long quantity = repository.sumQuantityByItem(itemId);
            return quantity == 0 ? Optional.empty() : Optional.of(quantity + " on hand");
        }
    }
}
