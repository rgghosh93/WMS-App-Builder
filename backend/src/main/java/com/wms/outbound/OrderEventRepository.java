package com.wms.outbound;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderEventRepository extends JpaRepository<OrderEvent, UUID> {

    List<OrderEvent> findByOrder_IdOrderByOccurredAtAsc(UUID orderId);
}
