package com.wms.outbound;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

public interface PackTaskRepository extends JpaRepository<PackTask, UUID>, JpaSpecificationExecutor<PackTask> {

    @Override
    @EntityGraph(attributePaths = {"container", "station", "pickTask", "assignedTo"})
    Page<PackTask> findAll(Specification<PackTask> spec, Pageable pageable);

    /** Tasks packing anything for this order. */
    @Query("select distinct t from PackTask t join t.details d where d.order.id = :orderId order by t.taskNumber")
    List<PackTask> findByOrderId(UUID orderId);

    /** The unfinished task for a container, if it is waiting to be packed. */
    @Query("select t from PackTask t where t.container.id = :containerId and t.status <> com.wms.outbound.PackTaskStatus.COMPLETED")
    Optional<PackTask> findOpenByContainer(UUID containerId);

    @Query("select t from PackTask t where t.station.id = :stationId and t.status <> com.wms.outbound.PackTaskStatus.COMPLETED")
    List<PackTask> findOpenByStation(UUID stationId);

    @EntityGraph(attributePaths = {"container", "station", "assignedTo"})
    List<PackTask> findByStatusNot(PackTaskStatus status);

    /** Unfinished tasks per station: rows of (station id, count). */
    @Query("select t.station.id, count(t) from PackTask t where t.status <> com.wms.outbound.PackTaskStatus.COMPLETED group by t.station.id")
    List<Object[]> countOpenByStation();

    @Query(value = "select nextval('pack_task_number_seq')", nativeQuery = true)
    long nextTaskNumber();

    @Query(value = "select nextval('carton_number_seq')", nativeQuery = true)
    long nextCartonNumber();
}
