package com.wms.outbound;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wms.inventory.InventoryRecord;
import com.wms.inventory.LocationLocks;
import com.wms.inventory.PlannedMoves;
import com.wms.masterdata.container.Container;
import com.wms.masterdata.location.Location;
import com.wms.masterdata.location.LocationType;
import com.wms.masterdata.zone.Zones;
import com.wms.masterdata.zone.ZoneFunction;
import com.wms.masterdata.zone.Zone;
import com.wms.outbound.OrderEvent.Type;

/**
 * Reserves stock for an order's lines and creates a pick task for each reservation.
 * <p>
 * Rules: only Available, unexpired stock of the line's item (and its lot, if the line asks for one);
 * stock already reserved for other lines is not used again; earliest expiry first, then
 * oldest received first. Stock inside containers counts, and is picked from the location
 * of the outermost container. Stock with no known location, or outside storage (such as at a
 * pack station), is skipped.
 */
@Service
@Transactional
public class AllocationService {

    private static final int MAX_NESTING = 20;
    /** Stands for picks in no pick zone. */
    private static final UUID NO_ZONE = new UUID(0, 0);

    private final AllocatableStockRepository stock;
    private final AllocationRepository allocations;
    private final PickTaskRepository tasks;
    private final OrderEventRepository events;

    private final LocationLocks locks;
    private final Zones zones;
    private final PlannedMoves moves;

    AllocationService(AllocatableStockRepository stock, AllocationRepository allocations,
                      PickTaskRepository tasks, OrderEventRepository events, LocationLocks locks, Zones zones,
                      PlannedMoves moves) {
        this.locks = locks;
        this.zones = zones;
        this.moves = moves;
        this.stock = stock;
        this.allocations = allocations;
        this.tasks = tasks;
        this.events = events;
    }

    /**
     * Allocates whatever each open line of the order still needs, then updates its status.
     * Everything allocated in this run goes onto new discrete pick tasks, one detail per allocation:
     * one task for each pick zone, and one for what is in none.
     *
     * @return the tasks created; none if nothing could be allocated
     */
    public List<PickTask> allocate(OutboundOrder order) {
        return allocate(List.of(order), PickMethod.DISCRETE, null);
    }

    /**
     * Allocates several orders together onto batch pick tasks, so a picker collects stock for all
     * of them in one walk of each pick zone. Details are numbered order by order, line by line.
     *
     * @return the tasks created; none if nothing could be allocated
     */
    public List<PickTask> allocateBatch(List<OutboundOrder> orders) {
        return allocate(orders, PickMethod.BATCH, null);
    }

    private List<PickTask> allocate(List<OutboundOrder> orders, PickMethod method, UUID itemId) {
        // Numbers are assigned only to tasks that get details, so backorders don't use up numbers.
        Map<UUID, PickTask> byZone = new LinkedHashMap<>();
        for (OutboundOrder order : orders) {
            allocateLines(order, itemId, zone -> byZone.computeIfAbsent(zone == null ? NO_ZONE : zone.getId(),
                    k -> new PickTask(UUID.randomUUID(), null, method, zone)));
        }
        List<PickTask> created = byZone.values().stream()
                .sorted(Comparator.comparing((PickTask t) -> t.getPickZone() == null ? "" : t.getPickZone().getCode())).toList();
        for (PickTask task : created) {
            task.assignNumber("PT%06d".formatted(tasks.nextTaskNumber()));
            tasks.save(task);
            List<OutboundOrder> of = task.orders();
            for (OutboundOrder order : of) {
                List<PickTaskDetail> details = task.getDetails().stream().filter(d -> d.getOrder().getId().equals(order.getId())).toList();
                int[] counts = {details.size(), details.stream().mapToInt(PickTaskDetail::getQuantity).sum()};
                events.save(new OrderEvent(order, Type.TASKS_CREATED, taskMessage(task, counts, of.size())));
            }
        }
        orders.forEach(OutboundOrder::refreshStatus);
        return created;
    }

    private static String taskMessage(PickTask task, int[] counts, int orderCount) {
        String details = "%d detail%s, %d EA%s".formatted(counts[0], counts[0] == 1 ? "" : "s", counts[1],
                task.getPickZone() == null ? "" : " in pick zone " + task.getPickZone().getCode());
        if (task.getPickMethod() == PickMethod.DISCRETE) {
            return "Created pick task %s with %s".formatted(task.getTaskNumber(), details);
        }
        int others = orderCount - 1;
        return "Added to batch pick task %s with %d other order%s: %s for this order".formatted(
                task.getTaskNumber(), others, others == 1 ? "" : "s", details);
    }

    /**
     * Allocates what an order still needs of one item, if there is any now.
     *
     * @return the tasks created
     */
    public List<PickTask> allocateItem(OutboundOrder order, UUID itemId) {
        return allocate(List.of(order), PickMethod.DISCRETE, itemId);
    }

    /** @param itemId only this item, saying nothing if there is none; everything if null */
    private void allocateLines(OutboundOrder order, UUID itemId, Function<Zone, PickTask> taskFor) {
        for (OrderLine line : order.getLines()) {
            if (line.getStatus() == LineStatus.CANCELLED || line.remaining() <= 0
                    || itemId != null && !line.getItem().getId().equals(itemId)) {
                continue;
            }
            int[] result = allocateLine(line, taskFor);
            int taken = result[0];
            int splits = result[1];
            line.allocated(taken);
            String what = line.getItem().getItemId() + (line.getRequestedLot() != null ? " lot " + line.getRequestedLot() : "");
            String from = splits > 1 ? " from " + splits + " places" : "";
            if (line.remaining() == 0) {
                events.save(new OrderEvent(order, Type.ALLOCATED, "Line %d: allocated %d EA of %s%s".formatted(
                        line.getLineNumber(), taken, what, from)));
            } else if (taken > 0) {
                events.save(new OrderEvent(order, Type.SHORT, "Line %d: allocated %d EA of %s%s; %d EA short".formatted(
                        line.getLineNumber(), taken, what, from, line.remaining())));
            } else if (itemId == null) {
                events.save(new OrderEvent(order, Type.SHORT, "Line %d: no available stock of %s; %d EA short".formatted(
                        line.getLineNumber(), what, line.remaining())));
            }
        }
    }

    /**
     * Reserves stock for one line, one allocation per inventory record used, each becoming a task detail.
     *
     * @return the eaches reserved, and from how many records
     */
    private int[] allocateLine(OrderLine line, Function<Zone, PickTask> taskFor) {
        List<InventoryRecord> candidates = line.getRequestedLot() == null
                ? stock.findForItem(line.getItem().getId(), LocalDate.now())
                : stock.findForItemAndLot(line.getItem().getId(), line.getRequestedLot(), LocalDate.now());
        if (candidates.isEmpty()) {
            return new int[] {0, 0};
        }
        Set<UUID> places = candidates.stream().map(AllocationService::locationOf).filter(Objects::nonNull).map(Location::getId)
                .collect(Collectors.toSet());
        Map<UUID, Zone> allocationZones = zones.of(ZoneFunction.ALLOCATION, places);
        Map<UUID, Zone> pickZones = zones.of(ZoneFunction.PICKING, places);
        // Earliest expiry first whatever the zone; then by the zone's priority; then oldest, as found.
        List<InventoryRecord> inOrder = candidates.stream().sorted(Comparator
                .comparing(InventoryRecord::getExpiryDate, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparingInt(r -> priority(r, allocationZones))).toList();

        // What is free goes first. If that isn't enough, stock set aside to be moved is taken back, unless someone is moving it.
        Map<InventoryRecord, Integer> take = new LinkedHashMap<>();
        int left = choose(inOrder, allocationZones, line.remaining(), take);
        if (left > 0) {
            Set<UUID> planned = moves.allocatedByInventory(inOrder.stream().map(InventoryRecord::getId).toList()).keySet();
            List<InventoryRecord> again = inOrder.stream().filter(r -> planned.contains(r.getId())).toList();
            for (InventoryRecord record : again) {
                if (left > 0 && mayUse(record, allocationZones)) {
                    moves.callOff(List.of(record.getId()), "Needed for order " + line.getOrder().getOrderNumber());
                    left = choose(List.of(record), allocationZones, left, take);
                }
            }
        }
        take.forEach((record, quantity) -> {
            Allocation allocation = allocations.save(new Allocation(UUID.randomUUID(), line, record, quantity));
            Location location = locationOf(record);
            taskFor.apply(pickZones.get(location.getId())).addDetail(allocation, location, record.getContainer());
        });
        return new int[] {line.remaining() - left, take.size()};
    }

    /**
     * Chooses free stock, in order.
     *
     * @param take what is chosen of each record, added to
     * @return what is still needed
     */
    private int choose(List<InventoryRecord> inOrder, Map<UUID, Zone> allocationZones, int needed, Map<InventoryRecord, Integer> take) {
        List<UUID> ids = inOrder.stream().map(InventoryRecord::getId).toList();
        Map<UUID, Long> reserved = new HashMap<>();
        for (Object[] row : allocations.sumByInventory(ids)) {
            reserved.put((UUID) row[0], ((Number) row[1]).longValue());
        }
        moves.allocatedByInventory(ids).forEach((id, n) -> reserved.merge(id, (long) n, Long::sum));
        int left = needed;
        for (InventoryRecord record : inOrder) {
            if (left <= 0) {
                break;
            }
            long free = record.getQuantity() - reserved.getOrDefault(record.getId(), 0L) - take.getOrDefault(record, 0);
            if (free > 0 && mayUse(record, allocationZones)) {
                int quantity = (int) Math.min(free, left);
                take.merge(record, quantity, Integer::sum);
                left -= quantity;
            }
        }
        return left;
    }

    private boolean mayUse(InventoryRecord record, Map<UUID, Zone> allocationZones) {
        Location location = locationOf(record);
        if (location == null || location.getType() != LocationType.STORAGE || locks.isLocked(location.getId())) {
            return false;
        }
        Zone zone = allocationZones.get(location.getId());
        return zone == null || zone.isAllocatable();
    }

    private static int priority(InventoryRecord record, Map<UUID, Zone> zones) {
        Location location = locationOf(record);
        Zone zone = location == null ? null : zones.get(location.getId());
        return zone == null || zone.getPriority() == null ? Zone.LAST : zone.getPriority();
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
