package com.wms.masterdata.uom;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UnitOfMeasureRepository extends JpaRepository<UnitOfMeasure, UUID> {

    Optional<UnitOfMeasure> findByUomCode(String uomCode);

    List<UnitOfMeasure> findAll(Sort sort);
}
