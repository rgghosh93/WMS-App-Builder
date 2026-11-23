package com.wms.putaway;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import com.wms.common.ActiveStatus;

public interface PutawayRuleRepository extends JpaRepository<PutawayRule, UUID> {

    @EntityGraph(attributePaths = {"item", "location", "zone"})
    List<PutawayRule> findAllByOrderBySequenceAscNameAsc();

    @EntityGraph(attributePaths = {"item", "location", "zone"})
    List<PutawayRule> findByStatusOrderBySequenceAscNameAsc(ActiveStatus status);

    Optional<PutawayRule> findFirstByNameIgnoreCase(String name);

    long countByZone_Id(UUID zoneId);

    long countByItem_Id(UUID itemId);

    long countByLocation_Id(UUID locationId);
}
