package com.wms.outbound;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wms.common.Codes;
import com.wms.common.api.ApiException;
import com.wms.inventory.InventoryRecord;
import com.wms.inventory.InventoryRepository;
import com.wms.inventory.InventoryStatus;
import com.wms.inventory.LocationLocks;
import com.wms.inventory.StockLedger;
import com.wms.inventory.StockLedger.Note;
import com.wms.inventory.StockLedger.Place;
import com.wms.inventory.StockTransaction;
import com.wms.masterdata.container.Container;
import com.wms.masterdata.item.Item;
import com.wms.masterdata.item.ItemScan;
import com.wms.masterdata.location.Location;
import com.wms.masterdata.location.LocationRepository;
import com.wms.masterdata.location.LocationType;
import com.wms.masterdata.zone.ZoneFunction;
import com.wms.masterdata.zone.Zones;
import com.wms.outbound.OrderEvent.Type;
import com.wms.outbound.OutboundViews.ItemRef;
import com.wms.outbound.PickingViews.ContainerTypeOption;
import com.wms.outbound.PickingViews.DropDirective;
import com.wms.outbound.PickingViews.NewContainer;
import com.wms.outbound.PickingViews.PickDirective;
import com.wms.outbound.PickingViews.PickZone;
import com.wms.outbound.PickingViews.PickingState;
import com.wms.outbound.PickingViews.Step;
import com.wms.outbound.PickingViews.TaskProgress;
import com.wms.outbound.PickingViews.UomQuantity;
import com.wms.users.AppUser;
import com.wms.users.AppUserRepository;
import com.wms.work.Directions;
import com.wms.work.Directions.Candidate;

/**
 * The picking transaction on a mobile device: scan an outbound container, get assigned a task,
 * work through its details in location order, then drop the container at a pack station.
 * <p>
 * Where a picker is in the transaction is worked out from the task, so someone who loses
 * signal or signs out carries on from the same pick.
 */
@Service
@Transactional
public class PickingService {

    static final Comparator<PickTaskDetail> WALK_ORDER = Comparator
            .comparing((PickTaskDetail d) -> d.getFromLocation().getLocationId())
            .thenComparing(d -> d.getFromContainer() == null ? "" : d.getFromContainer().getLpnId())
            .thenComparing(PickTaskDetail::getDetailNumber);

    private final PickTaskRepository tasks;
    private final AllocationRepository allocations;
    private final InventoryRepository inventory;
    private final LocationRepository locations;
    private final OutboundContainers outbound;
    private final AppUserRepository users;
    private final OrderEventRepository events;
    private final PackTaskRepository packTasks;
    private final PackingService packing;
    private final StockLedger ledger;
    private final LocationLocks locks;
    private final Zones zones;
    private final OrderRepository orders;
    private final Directions directions;

    PickingService(PickTaskRepository tasks, AllocationRepository allocations, InventoryRepository inventory,
                   LocationRepository locations, OutboundContainers outbound,
                   AppUserRepository users, OrderEventRepository events, PackTaskRepository packTasks,
                   PackingService packing,
                   StockLedger ledger, LocationLocks locks, Zones zones, OrderRepository orders,
                   Directions directions) {
        this.ledger = ledger;
        this.locks = locks;
        this.zones = zones;
        this.orders = orders;
        this.directions = directions;
        this.tasks = tasks;
        this.allocations = allocations;
        this.inventory = inventory;
        this.locations = locations;
        this.outbound = outbound;
        this.users = users;
        this.events = events;
        this.packTasks = packTasks;
        this.packing = packing;
    }

    /**
     * One confirmed pick, with everything the picker scanned.
     *
     * @param pickedSoFar  the detail's picked quantity the device was showing, so a repeated confirm isn't picked twice
     * @param itemBarcode  the scanned barcode or item ID; it decides the unit {@code quantity} is in
     * @param shortPick    there is no more to pick: close the detail with what was picked
     */
    public record Confirm(UUID detailId, Integer pickedSoFar, String locationId, String fromLpnId, String itemBarcode,
                          String serialNumber, Integer quantity, Boolean shortPick) {
    }

    @Transactional(readOnly = true)
    public PickingState state(UUID userId) {
        return stateOf(tasks.findActiveFor(userId).orElse(null));
    }

    /** Starts picking into a container: the most urgent waiting task is assigned to the picker. */
    public PickingState start(UUID userId, String scannedLpn, UUID cartonTypeId) {
        return start(userId, scannedLpn, cartonTypeId, false, null, null);
    }

    /** Pick zones, and how many tasks wait in each. */
    @Transactional(readOnly = true)
    public List<PickZone> zones() {
        Map<UUID, Long> waiting = new HashMap<>();
        tasks.countWaitingByZone().forEach(row -> waiting.put((UUID) row[0], (Long) row[1]));
        return zones.active(ZoneFunction.PICKING).stream()
                .map(z -> new PickZone(z.getId(), z.getCode(), z.getName(), waiting.getOrDefault(z.getId(), 0L))).toList();
    }

    /**
     * @param takeOver if someone else is picking into the container, carry on with their task
     * @param zoneId   only tasks in this pick zone; any if null
     * @param taskId   this task and no other; the most urgent if null
     */
    public PickingState start(UUID userId, String scannedLpn, UUID cartonTypeId, boolean takeOver, UUID zoneId, UUID taskId) {
        PickTask current = tasks.findActiveFor(userId).orElse(null);
        if (current != null) {
            throw ApiException.conflict("You are already picking " + current.getTaskNumber() + ".", Map.of());
        }
        String lpnId = Codes.normalize(scannedLpn);
        if (lpnId == null) {
            throw ApiException.invalid("Scan an outbound container", Map.of("lpnId", "scan an outbound container"));
        }
        PickTask theirs = tasks.findActiveInto(lpnId).orElse(null);
        if (theirs != null) {
            String picker = theirs.getAssignedTo().getDisplayName();
            if (!takeOver) {
                throw ApiException.conflict(theirs.getTaskNumber() + " is being picked into " + lpnId + " by " + picker + ".",
                        Map.of("assignedTo", picker));
            }
            theirs.reassign(users.getReferenceById(userId));
            tasks.flush();
            return stateOf(theirs);
        }
        outbound.check(lpnId);
        List<UUID> forMe = directions.arrange(userId, tasks.findWaiting(taskId == null ? zoneId : null).stream()
                .map(row -> new Candidate((UUID) row[0], ((Number) row[1]).intValue())).toList());
        List<UUID> waiting = taskId == null ? forMe : forMe.contains(taskId) ? List.of(taskId) : List.of();
        if (waiting.isEmpty()) {
            throw ApiException.conflict(taskId != null ? "That pick task isn't waiting for you any more."
                    : zoneId == null ? "No pick tasks are waiting." : "No pick tasks are waiting in that zone.", Map.of());
        }
        if (cartonTypeId == null && !outbound.exists(lpnId)) {
            List<ContainerTypeOption> types = outbound.cartonTypes().stream().map(ContainerTypeOption::of).toList();
            if (types.isEmpty()) {
                throw ApiException.invalid(lpnId + " is not a known container", Map.of("lpnId", "not a known container"));
            }
            return new PickingState(Step.CHOOSE_CONTAINER_TYPE, waiting.size(), null, null, null,
                    new NewContainer(lpnId, types));
        }

        directions.starting(userId);
        Container container = outbound.open(lpnId, cartonTypeId);
        AppUser user = users.getReferenceById(userId);
        for (UUID waitingId : waiting) {
            if (isLocked(waitingId)) {
                continue;
            }
            // Claims the task only if nobody else has in the meantime.
            if (tasks.claim(waitingId, user, container, Instant.now()) == 1) {
                return stateOf(tasks.findById(waitingId).orElseThrow());
            }
        }
        throw ApiException.conflict(waiting.stream().anyMatch(this::isLocked)
                ? "The waiting pick tasks are at locations being counted." : "No pick tasks are waiting.", Map.of());
    }

    /** Whether a task picks from a location being counted. */
    private boolean isLocked(UUID taskId) {
        return !locks.locked(tasks.findById(taskId).orElseThrow().getDetails().stream()
                .filter(d -> d.getStatus() == PickDetailStatus.OPEN).map(d -> d.getFromLocation().getId()).toList()).isEmpty();
    }

    /** Gives the task back, as long as nothing has been picked yet. */
    public PickingState release(UUID userId) {
        PickTask task = active(userId);
        if (task.anyPicked()) {
            throw ApiException.conflict("Stock has already been picked for " + task.getTaskNumber()
                    + ", so it can't be given back.", Map.of());
        }
        outbound.release(task);
        task.unassign();
        tasks.flush();
        return stateOf(null);
    }

    /** Checks the scans for a pick and moves the stock into the outbound container. */
    public PickingState confirm(UUID userId, Confirm request) {
        // Others may be picking the same order in other pick zones.
        if (request.detailId() != null) {
            orders.findIdOfPick(request.detailId()).ifPresent(orders::findLocked);
        }
        PickTask task = active(userId);
        PickTaskDetail detail = task.getDetails().stream()
                .filter(d -> d.getId().equals(request.detailId()) && d.getStatus() == PickDetailStatus.OPEN)
                .findFirst().orElse(null);
        if (detail == null || request.pickedSoFar() == null || request.pickedSoFar() != detail.getPickedQuantity()) {
            throw ApiException.conflict("This pick has changed. Carry on with the one shown now.", Map.of());
        }

        boolean shortPick = Boolean.TRUE.equals(request.shortPick());
        checkPlace(detail, request);
        if (locks.isLocked(detail.getFromLocation().getId())) {
            throw ApiException.conflict(detail.getFromLocation().getLocationId() + " is being counted. Pick there when that is done.", Map.of());
        }
        int eaches = 0;
        if (request.quantity() == null || request.quantity() < 0 || (request.quantity() == 0 && !shortPick)) {
            throw invalid("quantity", "Enter the quantity picked");
        }
        if (request.quantity() > 0) {
            eaches = checkedEaches(detail, request);
            InventoryRecord source = detail.getAllocation().getInventory();
            if (!source.isUsable()) {
                throw ApiException.conflict((source.isExpired() ? "This stock has expired. "
                        : "This stock was put on hold after it was allocated. ")
                        + "Leave it and report the pick short.", Map.of());
            }
            ledger.log(StockTransaction.Type.PICK, source, eaches, Place.of(source), Place.in(task.getToContainer()),
                    Note.by(userId, task.getTaskNumber()));
            move(detail, eaches, task.getToContainer());
        }

        AppUser user = users.getReferenceById(userId);
        detail.recordPick(eaches, user);
        detail.getLine().picked(eaches);
        if (detail.remaining() == 0) {
            detail.close(PickDetailStatus.PICKED);
        } else if (shortPick) {
            closeShort(detail, userId);
        }
        if (detail.getStatus() != PickDetailStatus.OPEN) {
            record(detail, task, user);
        }
        detail.getOrder().refreshStatus();
        if (task.noneOpen()) {
            if (task.anyPicked()) {
                task.finishPicking();
            } else {
                // Everything was short: there is nothing to drop.
                task.complete(null);
                outbound.release(task);
            }
        }
        tasks.flush();
        if (detail.getFromContainer() != null) {
            outbound.retireIfEmptied(detail.getFromContainer());
        }
        return state(userId);
    }

    /** Drops the full container at a pack station, which completes the task. */
    public PickingState drop(UUID userId, String scannedLocation) {
        PickTask task = active(userId);
        if (task.getStatus() != PickTaskStatus.PICKED) {
            throw ApiException.conflict("There is still stock to pick for " + task.getTaskNumber() + ".", Map.of());
        }
        String locationId = Codes.normalize(scannedLocation);
        Location station = locationId == null ? null : locations.findByLocationId(locationId).orElse(null);
        if (station == null) {
            throw invalid("locationId", "Scan a pack station");
        }
        if (station.getType() != LocationType.PACKING) {
            throw invalid("locationId", station.getLocationId() + " is not a pack station");
        }
        Container container = task.getToContainer();
        container.setCurrentLocation(station);
        task.complete(station);
        task.orders().stream().filter(o -> task.anyPickedFor(o)).forEach(o -> events.save(new OrderEvent(o, Type.DROPPED,
                "%s dropped at pack station %s (%s)".formatted(container.getLpnId(), station.getLocationId(), task.getTaskNumber()))));
        tasks.flush();
        packing.createFor(task, station);
        return stateOf(null);
    }

    // ---------- Checks ----------

    private static void checkPlace(PickTaskDetail detail, Confirm request) {
        String expected = detail.getFromLocation().getLocationId();
        if (!expected.equals(Codes.normalize(request.locationId()))) {
            throw invalid("locationId", "Wrong location. Go to " + expected);
        }
        // Nothing found at all: the container may be what is missing, so it needs no scan.
        boolean nothingPicked = request.quantity() != null && request.quantity() == 0;
        if (detail.getFromContainer() != null && !nothingPicked) {
            String lpn = detail.getFromContainer().getLpnId();
            if (!lpn.equals(Codes.normalize(request.fromLpnId()))) {
                throw invalid("fromLpnId", "Wrong container. Pick from " + lpn);
            }
        }
    }

    /** The confirmed quantity in eaches, after checking the item, serial and amount. */
    private static int checkedEaches(PickTaskDetail detail, Confirm request) {
        String scanned = Codes.normalize(request.itemBarcode());
        ItemScan scan = ItemScan.of(detail.getItem()).stream().filter(s -> s.value().equalsIgnoreCase(scanned)).findFirst()
                .orElseThrow(() -> invalid("itemBarcode", "Wrong item. Pick " + detail.getItem().getItemId()));
        if (detail.getSerialNumber() != null
                && !detail.getSerialNumber().equalsIgnoreCase(Codes.normalize(request.serialNumber()))) {
            throw invalid("serialNumber", "Wrong serial number. Pick " + detail.getSerialNumber());
        }
        long eaches = (long) request.quantity() * scan.eachesPerUnit();
        if (eaches > detail.remaining()) {
            throw invalid("quantity", "Too many: only " + detail.remaining() + " EA left to pick");
        }
        return (int) eaches;
    }

    private PickTask active(UUID userId) {
        return tasks.findActiveFor(userId)
                .orElseThrow(() -> ApiException.conflict("You have no pick task. Scan a container to start.", Map.of()));
    }

    private static ApiException invalid(String field, String message) {
        return ApiException.invalid(message, Map.of(field, message));
    }

    // ---------- Moving stock ----------

    /**
     * Moves picked stock into the outbound container. Its reservation moves with it, so it
     * stays reserved for the order and can't be allocated to another.
     */
    private void move(PickTaskDetail detail, int eaches, Container to) {
        Allocation source = detail.getAllocation();
        InventoryRecord from = source.getInventory();
        Allocation picked = detail.getPickedAllocation();
        if (picked == null && from.getQuantity() == eaches) {
            // The whole record: move it as it is (a serial number can't be on two records).
            from.placeIn(to);
            detail.moveAllocation();
            return;
        }
        if (picked == null) {
            InventoryRecord moved = copyOf(from, eaches);
            moved.placeIn(to);
            moved = inventory.save(moved);
            detail.setPickedAllocation(allocations.save(new Allocation(UUID.randomUUID(), detail.getLine(), moved, eaches)));
        } else {
            picked.changeQuantity(eaches);
            picked.getInventory().setQuantity(picked.getInventory().getQuantity() + eaches);
        }
        if (source.getQuantity() == eaches) {
            detail.clearAllocation();
            tasks.flush();
            allocations.delete(source);
            allocations.flush();
        } else {
            source.changeQuantity(-eaches);
        }
        if (from.getQuantity() == eaches) {
            inventory.delete(from);
        } else {
            from.setQuantity(from.getQuantity() - eaches);
        }
    }

    /**
     * Closes a detail with stock missing. The missing quantity goes on hold where it should
     * have been, so it isn't allocated again before someone counts it, and the line needs
     * that much stock again.
     */
    private void closeShort(PickTaskDetail detail, UUID userId) {
        int missing = detail.remaining();
        Allocation source = detail.getAllocation();
        InventoryRecord record = source.getInventory();
        detail.clearAllocation();
        detail.close(PickDetailStatus.SHORT);
        detail.getLine().releaseAllocated(missing);
        tasks.flush();
        allocations.delete(source);
        allocations.flush();
        if (record.getStatus() != InventoryStatus.AVAILABLE) {
            return;
        }
        String why = "Short pick";
        ledger.log(StockTransaction.Type.HOLD, record, Math.min(missing, record.getQuantity()), Place.of(record), Place.of(record),
                InventoryStatus.AVAILABLE, InventoryStatus.ON_HOLD, Note.by(userId, detail.getTask().getTaskNumber()).because(why));
        if (record.getQuantity() <= missing) {
            record.hold(InventoryStatus.ON_HOLD, why + ", " + detail.getTask().getTaskNumber());
            return;
        }
        record.setQuantity(record.getQuantity() - missing);
        InventoryRecord held = copyOf(record, missing);
        held.hold(InventoryStatus.ON_HOLD, why + ", " + detail.getTask().getTaskNumber());
        if (record.getContainer() != null) {
            held.placeIn(record.getContainer());
        } else {
            held.placeIn(record.getLocation());
        }
        inventory.save(held);
    }

    static InventoryRecord copyOf(InventoryRecord r, int quantity) {
        return r.copy(quantity);
    }

    private void record(PickTaskDetail detail, PickTask task, AppUser user) {
        String place = detail.getFromLocation().getLocationId();
        if (detail.getPickedQuantity() > 0) {
            events.save(new OrderEvent(detail.getOrder(), Type.PICKED,
                    "Line %d: %s picked %d EA of %s from %s into %s (%s detail %d)".formatted(
                            detail.getLine().getLineNumber(), user.getDisplayName(), detail.getPickedQuantity(),
                            detail.getItem().getItemId(), place, task.getToContainer().getLpnId(),
                            task.getTaskNumber(), detail.getDetailNumber())));
        }
        if (detail.getStatus() == PickDetailStatus.SHORT) {
            events.save(new OrderEvent(detail.getOrder(), Type.SHORT,
                    "Line %d: %d EA of %s missing at %s; put on hold to be counted (%s detail %d)".formatted(
                            detail.getLine().getLineNumber(), detail.remaining(), detail.getItem().getItemId(), place,
                            task.getTaskNumber(), detail.getDetailNumber())));
        }
    }

    // ---------- What to show ----------

    private PickingState stateOf(PickTask task) {
        long waiting = tasks.countByStatus(PickTaskStatus.OPEN);
        if (task == null) {
            return new PickingState(Step.SCAN_CONTAINER, waiting, null, null, null, null);
        }
        if (task.getStatus() == PickTaskStatus.PICKED) {
            return new PickingState(Step.DROP, waiting, progress(task), null, new DropDirective(suggestedStation()), null);
        }
        PickTaskDetail next = task.getDetails().stream()
                .filter(d -> d.getStatus() == PickDetailStatus.OPEN)
                .min(WALK_ORDER).orElseThrow();
        return new PickingState(Step.PICK, waiting, progress(task), directive(next), null, null);
    }

    private static TaskProgress progress(PickTask task) {
        List<PickTaskDetail> details = task.getDetails().stream()
                .filter(d -> d.getStatus() != PickDetailStatus.CANCELLED).toList();
        int closed = (int) details.stream().filter(d -> d.getStatus() != PickDetailStatus.OPEN).count();
        int orders = (int) details.stream().map(d -> d.getOrder().getId()).distinct().count();
        Container to = task.getToContainer();
        return new TaskProgress(task.getId(), task.getTaskNumber(), task.getPickMethod(),
                task.getPickZone() == null ? null : task.getPickZone().getCode(), orders, to.getLpnId(),
                to.getContainerType().getDescription(), closed, details.size(),
                task.getStatus() == PickTaskStatus.IN_PROGRESS && !task.anyPicked());
    }

    private static PickDirective directive(PickTaskDetail d) {
        Item item = d.getItem();
        List<ItemScan> scans = ItemScan.of(item);
        return new PickDirective(d.getId(), d.getDetailNumber(), d.getOrder().getOrderNumber(),
                d.getOrder().getCustomerName(), d.getFromLocation().getLocationId(),
                d.getFromContainer() == null ? null : d.getFromContainer().getLpnId(), ItemRef.of(item), d.getLotNumber(),
                d.getAllocation().getInventory().getExpiryDate(), d.getSerialNumber(), d.getQuantity(),
                d.getPickedQuantity(), d.remaining(), inUnits(d.remaining(), scans), scans);
    }

    /** Splits eaches into the largest units first, e.g. 27 EA at 6 per case is 4 CS and 3 EA. */
    static List<UomQuantity> inUnits(int eaches, List<ItemScan> scans) {
        List<UomQuantity> result = new ArrayList<>();
        int left = eaches;
        List<ItemScan> largestFirst = scans.stream()
                .sorted(Comparator.comparingInt(ItemScan::eachesPerUnit).reversed()).toList();
        for (ItemScan scan : largestFirst) {
            int count = left / scan.eachesPerUnit();
            if (count > 0) {
                result.add(new UomQuantity(scan.uom(), count, scan.eachesPerUnit()));
                left -= count * scan.eachesPerUnit();
            }
        }
        return result;
    }

    /** The pack station with the fewest containers waiting to be packed. */
    private String suggestedStation() {
        Map<UUID, Long> waiting = new HashMap<>();
        packTasks.countOpenByStation().forEach(row -> waiting.put((UUID) row[0], ((Number) row[1]).longValue()));
        return locations.findByTypeOrderByLocationId(LocationType.PACKING).stream()
                .min(Comparator.comparingLong((Location l) -> waiting.getOrDefault(l.getId(), 0L))
                        .thenComparing(Location::getLocationId))
                .map(Location::getLocationId).orElse(null);
    }
}
