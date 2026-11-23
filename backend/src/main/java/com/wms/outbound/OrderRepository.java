package com.wms.outbound;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

public interface OrderRepository extends JpaRepository<OutboundOrder, UUID>, JpaSpecificationExecutor<OutboundOrder> {

    /** Loads and locks an order, so several people working on it take turns. Before anything else of it is loaded. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from OutboundOrder o where o.id = :id")
    Optional<OutboundOrder> findLocked(UUID id);

    /** The order a pick is for. */
    @Query("select d.order.id from PickTaskDetail d where d.id = :detailId")
    Optional<UUID> findIdOfPick(UUID detailId);

    /** The orders in a container being packed, always in the same order so locking them can't deadlock. */
    @Query("select distinct o.id from PackTaskDetail d join d.order o where d.task.id = :packTaskId order by o.id")
    List<UUID> findIdsOfPackTask(UUID packTaskId);

    /** Eaches of an item that orders tried to get, any lot, and are short of. */
    @Query("""
            select coalesce(sum(l.orderedQuantity - l.closedQuantity - l.allocatedQuantity), 0) from OrderLine l join l.order o
            where l.item.id = :itemId and l.requestedLot is null and l.status <> com.wms.outbound.LineStatus.CANCELLED
            and o.status not in (com.wms.outbound.OrderStatus.NEW, com.wms.outbound.OrderStatus.CANCELLED,
                com.wms.outbound.OrderStatus.SHIPPED)""")
    long sumShortOf(UUID itemId);

    /** Orders that tried to get an item and are short of some, most urgent first. */
    @Query("""
            select o.id from OutboundOrder o where o.status not in (com.wms.outbound.OrderStatus.NEW,
                com.wms.outbound.OrderStatus.CANCELLED, com.wms.outbound.OrderStatus.SHIPPED)
            and exists (select 1 from OrderLine l where l.order = o and l.item.id = :itemId
                and l.status <> com.wms.outbound.LineStatus.CANCELLED
                and l.orderedQuantity - l.closedQuantity - l.allocatedQuantity > 0)
            order by o.priority asc, o.requestedShipDate asc nulls last, o.orderNumber asc""")
    List<UUID> findShortOf(UUID itemId);

    Optional<OutboundOrder> findByOrderNumber(String orderNumber);

    @Query(value = "select nextval('order_number_seq')", nativeQuery = true)
    long nextOrderNumber();
}
