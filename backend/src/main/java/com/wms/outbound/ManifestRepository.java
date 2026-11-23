package com.wms.outbound;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

public interface ManifestRepository extends JpaRepository<Manifest, UUID>, JpaSpecificationExecutor<Manifest> {

    @Override
    @EntityGraph(attributePaths = {"carrier", "door", "createdBy", "closedBy"})
    Page<Manifest> findAll(Specification<Manifest> spec, Pageable pageable);

    Optional<Manifest> findByOpenDoorId(UUID doorId);

    /** Loads and locks a manifest, so loading and closing it take turns. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Manifest m where m.id = :id")
    Optional<Manifest> findLocked(UUID id);

    long countByCarrier_Id(UUID carrierId);

    @Query(value = "select nextval('manifest_number_seq')", nativeQuery = true)
    long nextNumber();
}
