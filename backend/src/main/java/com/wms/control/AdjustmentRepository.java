package com.wms.control;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

public interface AdjustmentRepository extends JpaRepository<Adjustment, UUID> {

    @EntityGraph(attributePaths = "item")
    @Query("""
            select a from Adjustment a where (:status is null or a.status = :status) and (:q = '%' or lower(a.number) like :q escape '\\'
            or lower(a.item.itemId) like :q escape '\\' or lower(a.place) like :q escape '\\' or lower(a.requester) like :q escape '\\'
            or lower(a.reason) like :q escape '\\')""")
    Page<Adjustment> search(String q, Adjustment.Status status, Pageable page);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Adjustment a where a.id = :id")
    Optional<Adjustment> findLocked(UUID id);

    boolean existsByInventoryIdAndStatus(UUID inventoryId, Adjustment.Status status);

    @Query(value = "select nextval('adjustment_number_seq')", nativeQuery = true)
    long nextNumber();
}
