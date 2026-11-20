package com.wms.masterdata.container;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.wms.masterdata.location.LocationUsage;

/** Stops a location from being deleted while containers are recorded there. */
@Component
class ContainerLocationUsage implements LocationUsage {

    private final ContainerRepository repository;

    ContainerLocationUsage(ContainerRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<String> describeUse(UUID locationId) {
        long count = repository.countByCurrentLocation_Id(locationId);
        if (count == 0) {
            return Optional.empty();
        }
        return Optional.of(count == 1 ? "1 container is at this location" : count + " containers are at this location");
    }
}
