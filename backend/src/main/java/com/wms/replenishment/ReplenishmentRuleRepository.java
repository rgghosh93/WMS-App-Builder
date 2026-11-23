package com.wms.replenishment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import com.wms.common.ActiveStatus;

public interface ReplenishmentRuleRepository extends JpaRepository<ReplenishmentRule, UUID> {

    @EntityGraph(attributePaths = {"item", "location", "sourceZone"})
    List<ReplenishmentRule> findAllByOrderByLocation_LocationIdAscItem_ItemIdAsc();

    List<ReplenishmentRule> findByStatusOrderByLocation_LocationIdAscItem_ItemIdAsc(ActiveStatus status);

    /** An item's rules, in location order. */
    @EntityGraph(attributePaths = "location")
    List<ReplenishmentRule> findByItem_IdAndStatusOrderByLocation_LocationIdAsc(UUID itemId, ActiveStatus status);

    Optional<ReplenishmentRule> findByItem_IdAndLocation_Id(UUID itemId, UUID locationId);

    long countByItem_Id(UUID itemId);

    long countByLocation_Id(UUID locationId);

    long countBySourceZone_Id(UUID zoneId);
}
