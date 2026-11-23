package com.wms.outbound;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import com.wms.inventory.InventoryRecord;

import jakarta.persistence.LockModeType;

/**
 * Stock that may be allocated: available, not expired, of one item, in allocation order (earliest
 * expiry first, then oldest received). Rows are locked so two orders can't reserve
 * the same units at the same time.
 */
public interface AllocatableStockRepository extends Repository<InventoryRecord, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select i from InventoryRecord i
            where i.item.id = :itemId and i.status = com.wms.inventory.InventoryStatus.AVAILABLE
            and (i.expiryDate is null or i.expiryDate >= :today)
            order by i.expiryDate asc nulls last, i.receivedAt asc, i.id asc""")
    List<InventoryRecord> findForItem(UUID itemId, LocalDate today);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select i from InventoryRecord i
            where i.item.id = :itemId and i.lotNumber = :lot and i.status = com.wms.inventory.InventoryStatus.AVAILABLE
            and (i.expiryDate is null or i.expiryDate >= :today)
            order by i.expiryDate asc nulls last, i.receivedAt asc, i.id asc""")
    List<InventoryRecord> findForItemAndLot(UUID itemId, String lot, LocalDate today);
}
