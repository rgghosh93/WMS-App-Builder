package com.wms.facility;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.wms.common.ActiveStatus;

public interface FacilityRepository extends JpaRepository<Facility, UUID> {

    List<Facility> findAllByOrderByCodeAsc();

    List<Facility> findByStatusOrderByCodeAsc(ActiveStatus status);

    Optional<Facility> findByCode(String code);
}
