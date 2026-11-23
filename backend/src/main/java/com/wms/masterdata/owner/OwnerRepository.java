package com.wms.masterdata.owner;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OwnerRepository extends JpaRepository<Owner, UUID> {

    List<Owner> findAllByOrderByCodeAsc();

    Optional<Owner> findByCode(String code);
}
