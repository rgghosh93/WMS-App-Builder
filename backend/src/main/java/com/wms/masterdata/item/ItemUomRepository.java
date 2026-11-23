package com.wms.masterdata.item;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ItemUomRepository extends JpaRepository<ItemUom, UUID> {

    List<ItemUom> findAllByBarcode(String barcode);

    Optional<ItemUom> findFirstByBarcodeAndItem_OwnerId(String barcode, UUID ownerId);

    boolean existsByBarcode(String barcode);

    long countByUom_Id(UUID uomId);
}
