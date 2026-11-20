package com.wms.outbound;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface OrderLineRepository extends JpaRepository<OrderLine, UUID> {

    @Query("select count(l) from OrderLine l where l.item.id = :itemId and l.status <> com.wms.outbound.LineStatus.CANCELLED")
    long countOpenByItem(UUID itemId);
}
