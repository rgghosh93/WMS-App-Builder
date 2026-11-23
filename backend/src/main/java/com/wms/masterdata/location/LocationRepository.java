package com.wms.masterdata.location;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface LocationRepository extends JpaRepository<Location, UUID>, JpaSpecificationExecutor<Location> {

    Optional<Location> findByLocationId(String locationId);

    List<Location> findByTypeOrderByLocationId(LocationType type);

    List<Location> findByTypeAndAreaAndZoneOrderByLocationId(LocationType type, String area, String zone);

    boolean existsByType(LocationType type);

    Optional<Location> findByTypeAndAreaAndZoneAndAisleAndBayAndLevelAndPosition(
            LocationType type, String area, String zone, String aisle, String bay, String level, String position);
}
