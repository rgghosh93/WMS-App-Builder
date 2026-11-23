package com.wms.inventory;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import com.wms.masterdata.location.LocationType;

import jakarta.persistence.LockModeType;

public interface InventoryRepository extends JpaRepository<InventoryRecord, UUID> {

    /** Stock lying loose in any of these locations, with its item loaded. */
    @EntityGraph(attributePaths = {"item"})
    List<InventoryRecord> findByLocation_IdIn(Collection<UUID> locationIds);

    /** Stock inside any of these containers, with its item loaded. */
    @EntityGraph(attributePaths = {"item"})
    List<InventoryRecord> findByContainer_IdIn(Collection<UUID> containerIds);

    /** Loads and locks stock, so reservations and corrections of it take turns. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from InventoryRecord i where i.id in :ids order by i.id")
    List<InventoryRecord> findLocked(Collection<UUID> ids);

    /** Stock at locations of a type: loose, or in a container that stands there. */
    @Query("""
            select i from InventoryRecord i join fetch i.item left join fetch i.location l left join fetch i.container c
            left join fetch c.currentLocation cl
            where l.type = :type or (c.parent is null and cl.type = :type)
            order by l.locationId, cl.locationId, c.lpnId, i.id""")
    List<InventoryRecord> findAtType(LocationType type);

    List<InventoryRecord> findByItem_IdAndLotNumber(UUID itemId, String lotNumber);

    List<InventoryRecord> findByItem_Id(UUID itemId);

    Optional<InventoryRecord> findByItem_IdAndSerialNumber(UUID itemId, String serialNumber);

    long countByLocation_Id(UUID locationId);

    long countByContainer_Id(UUID containerId);

    boolean existsByItem_IdAndSerialNumber(UUID itemId, String serialNumber);

    @Query("select coalesce(sum(i.quantity), 0) from InventoryRecord i where i.item.id = :itemId")
    long sumQuantityByItem(UUID itemId);
}
