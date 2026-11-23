package com.wms.outbound;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import com.wms.masterdata.container.Container;
import com.wms.users.AppUser;

import jakarta.persistence.LockModeType;

public interface PickTaskRepository extends JpaRepository<PickTask, UUID>, JpaSpecificationExecutor<PickTask> {

    /** Loads and locks a task, so it isn't started while some of it is taken away. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from PickTask t where t.id = :id")
    Optional<PickTask> findLocked(UUID id);

    /** Tasks someone is picking with picks left at a location. */
    @Query("select distinct t.taskNumber from PickTask t join t.details d where t.status = com.wms.outbound.PickTaskStatus.IN_PROGRESS "
            + "and d.status = com.wms.outbound.PickDetailStatus.OPEN and d.fromLocation.id = :locationId")
    List<String> findBeingPickedAt(UUID locationId);

    /** Tasks with any detail picking for this order (a batch task picks for others too). */
    @Query("select distinct t from PickTask t join t.details d where d.order.id = :orderId order by t.taskNumber")
    List<PickTask> findByOrderId(UUID orderId);

    /** Per order: tasks with an open detail for that order. */
    @Query("select d.order.id, count(distinct d.task.id) from PickTaskDetail d where d.order.id in :orderIds "
            + "and d.status = com.wms.outbound.PickDetailStatus.OPEN group by d.order.id")
    List<Object[]> countOpenByOrder(Collection<UUID> orderIds);

    /** The task this picker is working on, if any. */
    @Query("select t from PickTask t where t.assignedTo.id = :userId and t.status in "
            + "(com.wms.outbound.PickTaskStatus.IN_PROGRESS, com.wms.outbound.PickTaskStatus.PICKED)")
    Optional<PickTask> findActiveFor(UUID userId);

    /** The task being picked into this container, if any. */
    @Query("select t from PickTask t where t.toContainer.lpnId = :lpnId and t.status in "
            + "(com.wms.outbound.PickTaskStatus.IN_PROGRESS, com.wms.outbound.PickTaskStatus.PICKED)")
    Optional<PickTask> findActiveInto(String lpnId);

    @Query("select t.taskNumber from PickTask t where t.toContainer.id = :containerId and t.status in "
            + "(com.wms.outbound.PickTaskStatus.IN_PROGRESS, com.wms.outbound.PickTaskStatus.PICKED)")
    Optional<String> findNumberActiveInto(UUID containerId);

    /** Tasks waiting for a picker, most urgent order first, then earliest ship date, then oldest: rows of (id, priority of the order). */
    @Query("""
            select t.id, min(o.priority) from PickTask t join t.details d join d.order o
            where t.status = com.wms.outbound.PickTaskStatus.OPEN and d.status = com.wms.outbound.PickDetailStatus.OPEN
            and (:zoneId is null or t.pickZone.id = :zoneId)
            group by t.id, t.taskNumber
            order by min(o.priority) asc, min(o.requestedShipDate) asc nulls last, t.taskNumber asc""")
    List<Object[]> findWaiting(UUID zoneId);

    @EntityGraph(attributePaths = {"assignedTo", "pickZone"})
    List<PickTask> findByStatusIn(Collection<PickTaskStatus> statuses);

    /** Waiting tasks in each pick zone: zone id, count. */
    @Query("select t.pickZone.id, count(t) from PickTask t where t.status = com.wms.outbound.PickTaskStatus.OPEN "
            + "and t.pickZone is not null group by t.pickZone.id")
    List<Object[]> countWaitingByZone();

    /** Assigns a waiting task. Returns 0 if someone else got it first. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update PickTask t set t.assignedTo = :user, t.toContainer = :container, t.startedAt = :now,
                t.updatedAt = :now, t.version = t.version + 1,
                t.status = com.wms.outbound.PickTaskStatus.IN_PROGRESS
            where t.id = :taskId and t.status = com.wms.outbound.PickTaskStatus.OPEN""")
    int claim(UUID taskId, AppUser user, Container container, Instant now);

    long countByStatus(PickTaskStatus status);

    @Query(value = "select nextval('pick_task_number_seq')", nativeQuery = true)
    long nextTaskNumber();
}
