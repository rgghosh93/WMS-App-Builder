package com.wms.outbound;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import com.wms.masterdata.container.Container;
import com.wms.users.AppUser;

public interface PickTaskRepository extends JpaRepository<PickTask, UUID>, JpaSpecificationExecutor<PickTask> {

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

    /** Tasks waiting for a picker: most urgent order first, then earliest ship date, then oldest. */
    @Query("""
            select t.id from PickTask t join t.details d join d.order o
            where t.status = com.wms.outbound.PickTaskStatus.OPEN and d.status = com.wms.outbound.PickDetailStatus.OPEN
            group by t.id, t.taskNumber
            order by min(o.priority) asc, min(o.requestedShipDate) asc nulls last, t.taskNumber asc""")
    List<UUID> findWaitingIds();

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
