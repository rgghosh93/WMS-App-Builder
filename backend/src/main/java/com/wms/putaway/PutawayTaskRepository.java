package com.wms.putaway;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

public interface PutawayTaskRepository extends JpaRepository<PutawayTask, UUID>, JpaSpecificationExecutor<PutawayTask> {

    @Override
    @EntityGraph(attributePaths = {"container", "container.containerType", "fromLocation", "directedLocation", "toLocation", "assignedTo"})
    Page<PutawayTask> findAll(Specification<PutawayTask> spec, Pageable pageable);

    /** Loads and locks a task, so two devices working on it take turns. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from PutawayTask t where t.id = :id")
    Optional<PutawayTask> findLocked(UUID id);

    Optional<PutawayTask> findFirstByContainer_IdAndStatusIn(UUID containerId, Collection<PutawayTaskStatus> statuses);

    Optional<PutawayTask> findFirstByAssignedTo_IdAndStatus(UUID userId, PutawayTaskStatus status);

    @EntityGraph(attributePaths = {"container", "container.containerType", "fromLocation"})
    List<PutawayTask> findByStatusOrderByCreatedAtAscTaskNumberAsc(PutawayTaskStatus status, Limit limit);

    /** Waiting, longest first. */
    @Query("select t.id from PutawayTask t where t.status = com.wms.putaway.PutawayTaskStatus.OPEN order by t.createdAt asc, t.taskNumber asc")
    List<UUID> findOpenIds();

    @EntityGraph(attributePaths = {"container", "container.containerType", "fromLocation", "assignedTo"})
    List<PutawayTask> findByStatusIn(Collection<PutawayTaskStatus> statuses);

    long countByStatus(PutawayTaskStatus status);

    /** Tasks on their way to any of these locations. */
    @Query("select t from PutawayTask t join fetch t.container where t.status = com.wms.putaway.PutawayTaskStatus.IN_PROGRESS "
            + "and t.directedLocation.id in :locationIds")
    List<PutawayTask> findHeadingFor(Collection<UUID> locationIds);

    @Query(value = "select nextval('putaway_task_number_seq')", nativeQuery = true)
    long nextNumber();
}
