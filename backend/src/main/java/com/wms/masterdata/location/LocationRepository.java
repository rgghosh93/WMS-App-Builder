package com.wms.masterdata.location;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface LocationRepository extends JpaRepository<Location, UUID>, JpaSpecificationExecutor<Location> {

    Optional<Location> findByLocationId(String locationId);

    Optional<Location> findByTypeAndAreaAndZoneAndAisleAndBayAndLevelAndPosition(
            LocationType type, String area, String zone, String aisle, String bay, String level, String position);
}
