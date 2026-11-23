package com.wms.masterdata.item;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.wms.masterdata.owner.Owner;

public interface ItemRepository extends JpaRepository<Item, UUID>, JpaSpecificationExecutor<Item> {

    Optional<Item> findByOwnerIdAndItemId(UUID ownerId, String itemId);

    /** Several owners may have an item with the same ID. */
    List<Item> findAllByItemId(String itemId);

    List<Item> findAllByBarcode(String barcode);

    Optional<Item> findByOwnerIdAndBarcode(UUID ownerId, String barcode);

    boolean existsByItemId(String itemId);

    boolean existsByBarcode(String barcode);

    /** An item of the first owner, for demo data. */
    default Optional<Item> findByItemId(String itemId) {
        return findByOwnerIdAndItemId(Owner.FIRST, itemId);
    }
}
