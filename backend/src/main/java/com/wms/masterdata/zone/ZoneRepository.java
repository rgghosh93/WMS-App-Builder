package com.wms.masterdata.zone;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import com.wms.common.ActiveStatus;

import jakarta.persistence.LockModeType;

public interface ZoneRepository extends JpaRepository<Zone, UUID> {

    List<Zone> findAllByOrderByFunctionAscCodeAsc();

    List<Zone> findByFunctionAndStatusOrderByCodeAsc(ZoneFunction function, ActiveStatus status);

    Optional<Zone> findByFunctionAndCode(ZoneFunction function, String code);

    /** Loads and locks a zone, so changes to its locations take turns. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select z from Zone z where z.id = :id")
    Optional<Zone> findLocked(UUID id);
}
