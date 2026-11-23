package com.wms.replenishment;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import com.wms.replenishment.ReplenishmentTask.Status;

import jakarta.persistence.LockModeType;

public interface ReplenishmentTaskRepository extends JpaRepository<ReplenishmentTask, UUID> {

    /** @param q lower case, with wildcards */
    @Query(value = """
            select t from ReplenishmentTask t join fetch t.item i join fetch t.fromLocation f join fetch t.toLocation d
            left join fetch t.assignedTo u
            where (:status is null or t.status = :status)
            and (:q = '%' or lower(t.taskNumber) like :q escape '\\' or lower(i.itemId) like :q escape '\\'
                or lower(i.description) like :q escape '\\' or lower(f.locationId) like :q escape '\\'
                or lower(d.locationId) like :q escape '\\' or lower(t.fromLpn) like :q escape '\\'
                or lower(t.lotNumber) like :q escape '\\' or lower(u.displayName) like :q escape '\\')""",
            countQuery = """
            select count(t) from ReplenishmentTask t join t.item i join t.fromLocation f join t.toLocation d left join t.assignedTo u
            where (:status is null or t.status = :status)
            and (:q = '%' or lower(t.taskNumber) like :q escape '\\' or lower(i.itemId) like :q escape '\\'
                or lower(i.description) like :q escape '\\' or lower(f.locationId) like :q escape '\\'
                or lower(d.locationId) like :q escape '\\' or lower(t.fromLpn) like :q escape '\\'
                or lower(t.lotNumber) like :q escape '\\' or lower(u.displayName) like :q escape '\\')""")
    Page<ReplenishmentTask> search(String q, Status status, Pageable page);

    /** Loads and locks a task, so two devices working on it take turns. Lock its stock first. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from ReplenishmentTask t where t.id = :id")
    Optional<ReplenishmentTask> findLocked(UUID id);

    @Query("select t.inventoryId from ReplenishmentTask t where t.id = :id")
    Optional<UUID> findInventoryId(UUID id);

    /** Not started, taking from any of this stock. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from ReplenishmentTask t where t.inventoryId in :inventoryIds "
            + "and t.status = com.wms.replenishment.ReplenishmentTask.Status.OPEN order by t.id")
    List<ReplenishmentTask> findOpenFrom(Collection<UUID> inventoryIds);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from ReplenishmentTask t where t.ruleId = :ruleId "
            + "and t.status = com.wms.replenishment.ReplenishmentTask.Status.OPEN order by t.id")
    List<ReplenishmentTask> findOpenOf(UUID ruleId);

    /** Set aside of each inventory record: rows of (inventory id, quantity). */
    @Query("""
            select t.inventoryId, sum(t.quantity - t.movedQuantity - t.shortQuantity) from ReplenishmentTask t
            where t.inventoryId in :inventoryIds and t.status in (com.wms.replenishment.ReplenishmentTask.Status.OPEN,
                com.wms.replenishment.ReplenishmentTask.Status.IN_PROGRESS)
            group by t.inventoryId""")
    List<Object[]> sumByInventory(Collection<UUID> inventoryIds);

    /** On its way: rows of (item id, location id, quantity). */
    @Query("""
            select t.item.id, t.toLocation.id, sum(t.quantity - t.movedQuantity - t.shortQuantity) from ReplenishmentTask t
            where t.item.id in :itemIds and t.status in (com.wms.replenishment.ReplenishmentTask.Status.OPEN,
                com.wms.replenishment.ReplenishmentTask.Status.IN_PROGRESS)
            group by t.item.id, t.toLocation.id""")
    List<Object[]> sumComing(Collection<UUID> itemIds);

    /** Waiting tasks whose stock can be used, most urgent first: rows of (id, from location id, to location id, reason). Not loaded, so they can be locked. */
    @Query("""
            select t.id, t.fromLocation.id, t.toLocation.id, t.reason from ReplenishmentTask t
            where t.status = com.wms.replenishment.ReplenishmentTask.Status.OPEN
            and exists (select 1 from InventoryRecord i where i.id = t.inventoryId
                and i.status = com.wms.inventory.InventoryStatus.AVAILABLE and (i.expiryDate is null or i.expiryDate >= :today))
            order by t.priority asc, t.createdAt asc, t.taskNumber asc""")
    List<Object[]> findWaiting(LocalDate today, Limit limit);

    @EntityGraph(attributePaths = {"item", "fromLocation", "toLocation", "assignedTo"})
    List<ReplenishmentTask> findByStatusInOrderByPriorityAscCreatedAtAscTaskNumberAsc(Collection<Status> statuses);

    Optional<ReplenishmentTask> findFirstByAssignedTo_IdAndStatus(UUID userId, Status status);

    @Query("select t.taskNumber from ReplenishmentTask t where t.status = com.wms.replenishment.ReplenishmentTask.Status.IN_PROGRESS "
            + "and (t.fromLocation.id = :locationId or t.toLocation.id = :locationId) order by t.taskNumber")
    List<String> findUnderWayAt(UUID locationId);

    long countByStatus(Status status);

    long countByItem_Id(UUID itemId);

    @Query("select count(t) from ReplenishmentTask t where t.fromLocation.id = :id or t.toLocation.id = :id")
    long countAt(UUID id);

    @Query(value = "select nextval('replenishment_task_number_seq')", nativeQuery = true)
    long nextNumber();
}
