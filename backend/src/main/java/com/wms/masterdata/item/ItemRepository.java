package com.wms.masterdata.item;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ItemRepository extends JpaRepository<Item, UUID>, JpaSpecificationExecutor<Item> {

    Optional<Item> findByItemId(String itemId);

    Optional<Item> findByBarcode(String barcode);
}
