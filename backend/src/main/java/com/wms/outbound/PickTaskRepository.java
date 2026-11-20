package com.wms.outbound;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

public interface PickTaskRepository extends JpaRepository<PickTask, UUID>, JpaSpecificationExecutor<PickTask> {

    /** Tasks with any detail picking for this order (a batch task picks for others too). */
    @Query("select distinct t from PickTask t join t.details d where d.order.id = :orderId order by t.taskNumber")
    List<PickTask> findByOrderId(UUID orderId);

    /** Per order: tasks with an open detail for that order. */
    @Query("select d.order.id, count(distinct d.task.id) from PickTaskDetail d where d.order.id in :orderIds "
            + "and d.status = com.wms.outbound.PickTaskStatus.OPEN group by d.order.id")
    List<Object[]> countOpenByOrder(Collection<UUID> orderIds);

    @Query(value = "select nextval('pick_task_number_seq')", nativeQuery = true)
    long nextTaskNumber();
}
