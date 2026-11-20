package com.wms.outbound;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

public interface OrderRepository extends JpaRepository<OutboundOrder, UUID>, JpaSpecificationExecutor<OutboundOrder> {

    Optional<OutboundOrder> findByOrderNumber(String orderNumber);

    @Query(value = "select nextval('order_number_seq')", nativeQuery = true)
    long nextOrderNumber();
}
