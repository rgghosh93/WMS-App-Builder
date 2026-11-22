package com.wms.outbound;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wms.inventory.InventoryRecord;
import com.wms.masterdata.container.Container;
import com.wms.masterdata.location.Location;
import com.wms.masterdata.location.LocationType;
import com.wms.outbound.OrderEvent.Type;

/**
 * Reserves stock for an order's lines and creates a pick task for each reservation.
 * <p>
 * Rules: only Available stock of the line's item (and its lot, if the line asks for one);
 * stock already reserved for other lines is not used again; earliest expiry first, then
 * oldest received first. Stock inside containers counts, and is picked from the location
 * of the outermost container. Stock with no known location, or outside storage (such as at a
 * pack station), is skipped.
 */
@Service
@Transactional
public class AllocationService {

    private static final int MAX_NESTING = 20;

    private final AllocatableStockRepository stock;
    private final AllocationRepository allocations;
    private final PickTaskRepository tasks;
    private final OrderEventRepository events;

    AllocationService(AllocatableStockRepository stock, AllocationRepository allocations,
                      PickTaskRepository tasks, OrderEventRepository events) {
        this.stock = stock;
        this.allocations = allocations;
        this.tasks = tasks;
        this.events = events;
    }

    /**
     * Allocates whatever each open line of the order still needs, then updates its status.
     * Everything allocated in this run goes onto one new discrete pick task, one detail per allocation.
     *
     * @return the task created, or null if nothing could be allocated
     */
    public PickTask allocate(OutboundOrder order) {
        return allocate(List.of(order), PickMethod.DISCRETE);
    }

    /**
     * Allocates several orders together onto one batch pick task, so a picker collects
     * stock for all of them in one walk. Details are numbered order by order, line by line.
     *
     * @return the task created, or null if nothing could be allocated
     */
    public PickTask allocateBatch(List<OutboundOrder> orders) {
        return allocate(orders, PickMethod.BATCH);
    }

    private PickTask allocate(List<OutboundOrder> orders, PickMethod method) {
        // The number is assigned only if the task gets details, so backorders don't use up numbers.
        PickTask task = new PickTask(UUID.randomUUID(), null, method);
        Map<OutboundOrder, int[]> added = new LinkedHashMap<>();
        for (OutboundOrder order : orders) {
            int before = task.getDetails().size();
            int eaches = task.totalQuantity();
            allocateLines(order, task);
            if (task.getDetails().size() > before) {
                added.put(order, new int[] {task.getDetails().size() - before, task.totalQuantity() - eaches});
            }
        }
        if (!task.getDetails().isEmpty()) {
            task.assignNumber("PT%06d".formatted(tasks.nextTaskNumber()));
            tasks.save(task);
            added.forEach((order, counts) -> events.save(new OrderEvent(order, Type.TASKS_CREATED, taskMessage(task, counts, added.size()))));
        }
        orders.forEach(OutboundOrder::refreshStatus);
        return task.getDetails().isEmpty() ? null : task;
    }

    private static String taskMessage(PickTask task, int[] counts, int orderCount) {
        String details = "%d detail%s, %d EA".formatted(counts[0], counts[0] == 1 ? "" : "s", counts[1]);
        if (task.getPickMethod() == PickMethod.DISCRETE) {
            return "Created pick task %s with %s".formatted(task.getTaskNumber(), details);
        }
        int others = orderCount - 1;
        return "Added to batch pick task %s with %d other order%s: %s for this order".formatted(
                task.getTaskNumber(), others, others == 1 ? "" : "s", details);
    }

    private void allocateLines(OutboundOrder order, PickTask task) {
        for (OrderLine line : order.getLines()) {
            if (line.getStatus() == LineStatus.CANCELLED || line.remaining() <= 0) {
                continue;
            }
            int before = task.getDetails().size();
            int taken = allocateLine(line, task);
            int splits = task.getDetails().size() - before;
            line.allocated(taken);
            String what = line.getItem().getItemId() + (line.getRequestedLot() != null ? " lot " + line.getRequestedLot() : "");
            String from = splits > 1 ? " from " + splits + " places" : "";
            if (line.remaining() == 0) {
                events.save(new OrderEvent(order, Type.ALLOCATED, "Line %d: allocated %d EA of %s%s".formatted(
                        line.getLineNumber(), taken, what, from)));
            } else if (taken > 0) {
                events.save(new OrderEvent(order, Type.SHORT, "Line %d: allocated %d EA of %s%s; %d EA short".formatted(
                        line.getLineNumber(), taken, what, from, line.remaining())));
            } else {
                events.save(new OrderEvent(order, Type.SHORT, "Line %d: no available stock of %s; %d EA short".formatted(
                        line.getLineNumber(), what, line.remaining())));
            }
        }
    }

    /** Reserves stock for one line, one allocation per inventory record used, each becoming a task detail. */
    private int allocateLine(OrderLine line, PickTask task) {
        List<InventoryRecord> candidates = line.getRequestedLot() == null
                ? stock.findForItem(line.getItem().getId())
                : stock.findForItemAndLot(line.getItem().getId(), line.getRequestedLot());
        if (candidates.isEmpty()) {
            return 0;
        }
        Map<UUID, Long> reserved = new HashMap<>();
        for (Object[] row : allocations.sumByInventory(candidates.stream().map(InventoryRecord::getId).toList())) {
            reserved.put((UUID) row[0], ((Number) row[1]).longValue());
        }

        int needed = line.remaining();
        int taken = 0;
        for (InventoryRecord record : candidates) {
            if (taken >= needed) {
                break;
            }
            long free = record.getQuantity() - reserved.getOrDefault(record.getId(), 0L);
            Location location = locationOf(record);
            if (free <= 0 || location == null || location.getType() != LocationType.STORAGE) {
                continue;
            }
            int quantity = (int) Math.min(free, needed - taken);
            Allocation allocation = allocations.save(new Allocation(UUID.randomUUID(), line, record, quantity));
            task.addDetail(allocation, location, record.getContainer());
            taken += quantity;
        }
        return taken;
    }

    /** Where to pick the stock: its own location, or the location of its outermost container. */
    static Location locationOf(InventoryRecord record) {
        if (record.getLocation() != null) {
            return record.getLocation();
        }
        Container container = record.getContainer();
        for (int depth = 0; container != null && container.getParent() != null && depth < MAX_NESTING; depth++) {
            container = container.getParent();
        }
        return container == null ? null : container.getCurrentLocation();
    }
}
