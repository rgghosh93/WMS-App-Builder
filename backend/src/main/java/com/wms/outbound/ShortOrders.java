package com.wms.outbound;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.wms.inventory.WaitingOrders;

/** Orders that tried to get stock and are still short of some. Orders not allocated yet are left alone. */
@Component
class ShortOrders implements WaitingOrders {

    private final OrderRepository orders;
    private final AllocationService allocationService;

    ShortOrders(OrderRepository orders, AllocationService allocationService) {
        this.orders = orders;
        this.allocationService = allocationService;
    }

    @Override
    @Transactional(readOnly = true)
    public int shortOf(UUID itemId) {
        return (int) Math.min(orders.sumShortOf(itemId), Integer.MAX_VALUE);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> waitingFor(UUID itemId) {
        return orders.findShortOf(itemId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> itemsWaitedFor() {
        return orders.findItemsShortOf();
    }

    @Override
    @Transactional
    public boolean tryAgain(UUID orderId, UUID itemId) {
        OutboundOrder order = orders.findLocked(orderId).orElse(null);
        // Nothing is tried again for an order taken back to New in the meantime: that is up to whoever did it.
        if (order == null || order.getStatus() == OrderStatus.CANCELLED || order.getStatus() == OrderStatus.NEW || !order.needsStock()) {
            return true;
        }
        allocationService.allocateItem(order, itemId);
        orders.flush();
        return order.getLines().stream().noneMatch(l -> l.getItem().getId().equals(itemId) && l.getRequestedLot() == null
                && l.getStatus() != LineStatus.CANCELLED && l.remaining() > 0);
    }
}
