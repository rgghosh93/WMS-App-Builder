package com.wms.masterdata.item;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ItemUomRepository extends JpaRepository<ItemUom, UUID> {

    Optional<ItemUom> findFirstByBarcode(String barcode);

    long countByUom_Id(UUID uomId);
}
