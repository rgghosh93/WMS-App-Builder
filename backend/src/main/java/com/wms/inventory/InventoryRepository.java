package com.wms.inventory;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface InventoryRepository extends JpaRepository<InventoryRecord, UUID> {

    /** Stock lying loose in any of these locations, with its item loaded. */
    @EntityGraph(attributePaths = {"item"})
    List<InventoryRecord> findByLocation_IdIn(Collection<UUID> locationIds);

    /** Stock inside any of these containers, with its item loaded. */
    @EntityGraph(attributePaths = {"item"})
    List<InventoryRecord> findByContainer_IdIn(Collection<UUID> containerIds);

    long countByLocation_Id(UUID locationId);

    long countByContainer_Id(UUID containerId);

    @Query("select coalesce(sum(i.quantity), 0) from InventoryRecord i where i.item.id = :itemId")
    long sumQuantityByItem(UUID itemId);
}
