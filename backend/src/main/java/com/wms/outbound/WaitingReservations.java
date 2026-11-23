package com.wms.outbound;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.wms.inventory.StockReservations;
import com.wms.outbound.OrderEvent.Type;

/** Takes reserved stock away from orders nobody has started picking, and finds them other stock. */
@Component
class WaitingReservations implements StockReservations {

    private final AllocationRepository allocations;
    private final PickTaskRepository tasks;
    private final PickTaskDetailRepository details;
    private final OrderRepository orders;
    private final OrderEventRepository events;
    private final AllocationService allocationService;

    WaitingReservations(AllocationRepository allocations, PickTaskRepository tasks, PickTaskDetailRepository details,
                        OrderRepository orders, OrderEventRepository events, AllocationService allocationService) {
        this.allocations = allocations;
        this.tasks = tasks;
        this.details = details;
        this.orders = orders;
        this.events = events;
        this.allocationService = allocationService;
    }

    @Override
    public Released release(Map<UUID, Integer> keep, String why) {
        Set<UUID> orderIds = new LinkedHashSet<>();
        int released = 0;
        if (keep.isEmpty()) {
            return new Released(orderIds, 0);
        }
        Map<UUID, Integer> reserved = new HashMap<>();
        var all = allocations.findByInventory_IdInOrderByCreatedAtDesc(keep.keySet());
        all.forEach(a -> reserved.merge(a.getInventory().getId(), a.getQuantity(), Integer::sum));
        for (Allocation allocation : all) {
            UUID record = allocation.getInventory().getId();
            if (reserved.get(record) <= keep.get(record)) {
                continue;
            }
            PickTaskDetail detail = details.findFirstByAllocation_Id(allocation.getId()).orElse(null);
            if (detail == null || detail.getStatus() != PickDetailStatus.OPEN) {
                continue;
            }
            PickTask task = tasks.findLocked(detail.getTask().getId()).orElseThrow();
            if (task.getStatus() != PickTaskStatus.OPEN) {
                continue; // someone is picking it
            }
            int quantity = allocation.getQuantity();
            OrderLine line = allocation.getLine();
            String from = detail.getFromContainer() == null ? detail.getFromLocation().getLocationId() : detail.getFromContainer().getLpnId();
            task.cancelDetail(detail);
            line.releaseAllocated(quantity);
            line.getOrder().refreshStatus();
            events.save(new OrderEvent(line.getOrder(), Type.SHORT, "Line %d: let go of %d EA of %s reserved in %s (%s): %s".formatted(
                    line.getLineNumber(), quantity, line.getItem().getItemId(), from, task.getTaskNumber(), why)));
            tasks.flush();
            allocations.delete(allocation);
            allocations.flush();
            reserved.merge(record, -quantity, Integer::sum);
            orderIds.add(line.getOrder().getId());
            released += quantity;
        }
        return new Released(orderIds, released);
    }

    @Override
    public void reallocate(Released released) {
        for (UUID id : released.orderIds()) {
            OutboundOrder order = orders.findById(id).orElseThrow();
            if (order.getStatus() != OrderStatus.CANCELLED && order.needsStock()) {
                allocationService.allocate(order);
            }
        }
        orders.flush();
    }

    @Override
    public Optional<String> beingPicked(UUID locationId) {
        return tasks.findBeingPickedAt(locationId).stream().findFirst().map(n -> n + " is being picked there");
    }
}
