package com.wms.control;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

public interface CountTaskRepository extends JpaRepository<CountTask, UUID> {

    @EntityGraph(attributePaths = "location")
    @Query("""
            select t from CountTask t where (:status is null or t.status = :status) and (:q = '%' or lower(t.number) like :q escape '\\'
            or lower(t.location.locationId) like :q escape '\\' or lower(t.counter) like :q escape '\\')""")
    Page<CountTask> search(String q, CountTask.Status status, Pageable page);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from CountTask t where t.id = :id")
    Optional<CountTask> findLocked(UUID id);

    Optional<CountTask> findFirstByLocation_IdAndStatusIn(UUID locationId, Collection<CountTask.Status> statuses);

    Optional<CountTask> findFirstByCounterIdAndStatus(UUID counterId, CountTask.Status status);

    @Query("select t.location.id from CountTask t where t.location.id in :locationIds and t.status in :statuses")
    Set<UUID> findLocations(Collection<UUID> locationIds, Collection<CountTask.Status> statuses);

    /** Next to count, in walking order; what has to be counted again first. */
    @EntityGraph(attributePaths = "location")
    @Query("select t from CountTask t where t.status in :statuses order by t.status desc, t.location.locationId asc")
    List<CountTask> findWaiting(Collection<CountTask.Status> statuses);

    long countByStatusIn(Collection<CountTask.Status> statuses);

    @Query(value = "select nextval('count_number_seq')", nativeQuery = true)
    long nextNumber();
}
