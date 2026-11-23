package com.wms.replenishment;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import com.wms.inventory.InventoryRecord;

import jakarta.persistence.LockModeType;

/** Good stock of an item, earliest expiry first, then oldest received. */
interface ReplenishmentStockRepository extends Repository<InventoryRecord, UUID> {

    String GOOD = """
            select i from InventoryRecord i where i.item.id = :itemId and i.status = com.wms.inventory.InventoryStatus.AVAILABLE
            and (i.expiryDate is null or i.expiryDate >= :today) order by i.expiryDate asc nulls last, i.receivedAt asc, i.id asc""";

    @Query(GOOD)
    List<InventoryRecord> find(UUID itemId, LocalDate today);

    /** Locked, so orders and replenishment don't set the same units aside. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(GOOD)
    List<InventoryRecord> findLocked(UUID itemId, LocalDate today);
}
