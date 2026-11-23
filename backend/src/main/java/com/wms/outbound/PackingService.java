package com.wms.outbound;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wms.common.Codes;
import com.wms.common.api.ApiException;
import com.wms.common.measure.WeightUom;
import com.wms.inventory.InventoryRecord;
import com.wms.inventory.InventoryRepository;
import com.wms.inventory.InventoryStatus;
import com.wms.inventory.StockLedger;
import com.wms.inventory.StockLedger.Note;
import com.wms.inventory.StockLedger.Place;
import com.wms.inventory.StockTransaction;
import com.wms.masterdata.container.Container;
import com.wms.masterdata.container.ContainerCategory;
import com.wms.masterdata.container.ContainerRepository;
import com.wms.masterdata.item.ItemScan;
import com.wms.masterdata.location.Location;
import com.wms.masterdata.location.LocationRepository;
import com.wms.masterdata.location.LocationType;
import com.wms.outbound.OrderEvent.Type;
import com.wms.outbound.OutboundViews.LocationRef;
import com.wms.outbound.PackingViews.PackTaskView;
import com.wms.outbound.PackingViews.StationQueue;
import com.wms.outbound.PackingViews.StationView;
import com.wms.outbound.PickingViews.ContainerTypeOption;
import com.wms.users.AppUser;
import com.wms.users.AppUserRepository;
import com.wms.work.Directions;

/**
 * Packing at a pack station: scan a dropped container, pack what is in it into cartons (each
 * for one order), and close them. Every scan moves real stock, and its reservation, into the carton.
 */
@Service
@Transactional
public class PackingService {

    private static final BigDecimal MAX_WEIGHT = new BigDecimal("999999999.999");

    private final PackTaskRepository tasks;
    private final OrderRepository orders;
    private final Directions directions;
    private final AllocationRepository allocations;
    private final InventoryRepository inventory;
    private final LocationRepository locations;
    private final ContainerRepository containers;
    private final OutboundContainers outbound;
    private final AppUserRepository users;
    private final OrderEventRepository events;
    private final ShippingService shipping;

    private final StockLedger ledger;

    PackingService(PackTaskRepository tasks, AllocationRepository allocations, InventoryRepository inventory,
                   LocationRepository locations, ContainerRepository containers, OutboundContainers outbound,
                   AppUserRepository users, OrderEventRepository events, ShippingService shipping, StockLedger ledger, OrderRepository orders, Directions directions) {
        this.orders = orders;
        this.directions = directions;
        this.ledger = ledger;
        this.tasks = tasks;
        this.allocations = allocations;
        this.inventory = inventory;
        this.locations = locations;
        this.containers = containers;
        this.outbound = outbound;
        this.users = users;
        this.events = events;
        this.shipping = shipping;
    }

    /**
     * One scan of an item into a carton.
     *
     * @param packedSoFar the task's packed quantity the station was showing, so a repeated scan isn't packed twice
     * @param itemBarcode the scanned barcode or item ID; it decides the unit {@code quantity} is in
     * @param lotNumber   needed only when the order has the item in more than one lot
     */
    public record Pack(UUID cartonId, Integer packedSoFar, String itemBarcode, String serialNumber, String lotNumber,
                       Integer quantity) {
    }

    // ---------- Creating and listing tasks ----------

    /** Creates the packing task for a container just dropped at a station. */
    void createFor(PickTask pickTask, Location station) {
        // What was picked for an order since cancelled or undone isn't packed: it goes back to storage from here.
        List<PickTaskDetail> picked = pickTask.getDetails().stream()
                .filter(d -> d.getPickedQuantity() > 0 && d.getStatus() != PickDetailStatus.CANCELLED).toList();
        if (picked.isEmpty()) {
            return;
        }
        PackTask task = new PackTask(UUID.randomUUID(), "PK%06d".formatted(tasks.nextTaskNumber()), pickTask,
                pickTask.getToContainer(), station);
        picked.stream()
                .sorted(Comparator.comparing((PickTaskDetail d) -> d.getOrder().getOrderNumber())
                        .thenComparing(d -> d.getLine().getLineNumber())
                        .thenComparing(PickTaskDetail::getDetailNumber))
                .forEach(task::addDetail);
        tasks.save(task);
        task.orders().forEach(o -> events.save(new OrderEvent(o, Type.TASKS_CREATED,
                "Created packing task %s for %s at %s".formatted(task.getTaskNumber(), task.getContainer().getLpnId(),
                        station.getLocationId()))));
    }

    @Transactional(readOnly = true)
    public Page<PackTaskView> search(PackTaskSearch search, Pageable pageable) {
        return tasks.findAll(search.toSpecification(), pageable).map(PackTaskView::of);
    }

    @Transactional(readOnly = true)
    public PackTaskView get(UUID id) {
        return PackTaskView.of(load(id));
    }

    @Transactional(readOnly = true)
    public List<PackTaskView> forOrder(UUID orderId) {
        return tasks.findByOrderId(orderId).stream().map(PackTaskView::of).toList();
    }

    @Transactional(readOnly = true)
    public List<StationView> stations() {
        Map<UUID, Long> waiting = new HashMap<>();
        tasks.countOpenByStation().forEach(row -> waiting.put((UUID) row[0], ((Number) row[1]).longValue()));
        return locations.findByTypeOrderByLocationId(LocationType.PACKING).stream()
                .map(l -> new StationView(l.getId(), l.getLocationId(), waiting.getOrDefault(l.getId(), 0L))).toList();
    }

    @Transactional(readOnly = true)
    public List<ContainerTypeOption> cartonTypes() {
        return outbound.cartonTypes().stream().map(ContainerTypeOption::of).toList();
    }

    @Transactional(readOnly = true)
    public StationQueue queue(String locationId) {
        Location station = station(locationId);
        List<PackTask> open = tasks.findOpenByStation(station.getId());
        Map<UUID, Integer> priorities = directions.priorities(open.stream().collect(Collectors.toMap(PackTask::getId, PackTask::priority)));
        Comparator<PackTask> urgency = Comparator
                .comparing((PackTask t) -> priorities.get(t.getId()))
                .thenComparing(t -> t.orders().stream().map(OutboundOrder::getRequestedShipDate).filter(Objects::nonNull)
                        .min(Comparator.naturalOrder()).orElse(LocalDate.MAX))
                .thenComparing(PackTask::getCreatedAt)
                .thenComparing(PackTask::getTaskNumber);
        return new StationQueue(LocationRef.of(station),
                open.stream().sorted(urgency).map(PackTaskView::of).toList());
    }

    // ---------- Packing ----------

    /**
     * Starts packing the scanned container, or carries on with it.
     *
     * @param takeOver carry on with a task someone else started
     */
    public PackTaskView start(UUID userId, String locationId, String scannedLpn, boolean takeOver) {
        Location station = station(locationId);
        String lpnId = Codes.normalize(scannedLpn);
        Container container = lpnId == null ? null : containers.findByLpnId(lpnId).orElse(null);
        if (container == null) {
            throw invalid("lpnId", lpnId == null ? "Scan a container" : lpnId + " is not a known container");
        }
        PackTask task = tasks.findOpenByContainer(container.getId())
                .orElseThrow(() -> invalid("lpnId", lpnId + " has nothing to pack"));
        if (!task.getStation().getId().equals(station.getId())) {
            throw invalid("lpnId", lpnId + " was dropped at " + task.getStation().getLocationId());
        }
        AppUser packer = task.getAssignedTo();
        if (packer != null && !packer.getId().equals(userId) && !takeOver) {
            throw ApiException.conflict(task.getTaskNumber() + " is being packed by " + packer.getDisplayName() + ".",
                    Map.of("assignedTo", packer.getDisplayName()));
        }
        if (packer == null || !packer.getId().equals(userId)) {
            task.start(users.getReferenceById(userId));
            tasks.flush();
        }
        return PackTaskView.of(task);
    }

    /** Opens a carton for an order. Leave the LPN blank to have one made. */
    public PackTaskView openCarton(UUID userId, UUID taskId, UUID orderId, UUID containerTypeId, String scannedLpn) {
        PackTask task = active(userId, taskId);
        OutboundOrder order = task.orders().stream().filter(o -> o.getId().equals(orderId)).findFirst()
                .orElseThrow(() -> invalid("orderId", "This order is not in " + task.getContainer().getLpnId()));
        task.getCartons().stream()
                .filter(c -> c.getStatus() == CartonStatus.OPEN && c.getOrder().getId().equals(orderId)).findFirst()
                .ifPresent(c -> {
                    throw ApiException.conflict("Close " + c.getContainer().getLpnId() + " first.", Map.of());
                });
        if (task.getDetails().stream().noneMatch(d -> d.getOrder().getId().equals(orderId) && d.remaining() > 0)) {
            throw ApiException.conflict("Everything for " + order.getOrderNumber() + " is already packed.", Map.of());
        }
        String lpnId = Codes.normalize(scannedLpn);
        if (lpnId == null) {
            do {
                lpnId = "CT%08d".formatted(tasks.nextCartonNumber());
            } while (outbound.exists(lpnId));
        } else {
            outbound.checkNewCarton(lpnId);
        }
        Container container = outbound.open(lpnId, containerTypeId);
        container.setCurrentLocation(task.getStation());
        task.getCartons().add(new PackCarton(UUID.randomUUID(), task, order, container));
        return saved(task);
    }

    /** Packs a scanned quantity of an item into an open carton. */
    public PackTaskView pack(UUID userId, UUID taskId, Pack request) {
        PackTask task = active(userId, taskId);
        PackCarton carton = openCarton(task, request.cartonId());
        int packedSoFar = task.getDetails().stream().mapToInt(PackTaskDetail::getPackedQuantity).sum();
        if (request.packedSoFar() == null || request.packedSoFar() != packedSoFar) {
            throw ApiException.conflict("This container has changed. Check what is packed and scan again.", Map.of());
        }
        if (request.quantity() == null || request.quantity() < 1) {
            throw invalid("quantity", "Enter the quantity packed");
        }

        String scanned = Codes.normalize(request.itemBarcode());
        List<PackTaskDetail> matches = task.getDetails().stream()
                .filter(d -> ItemScan.of(d.getItem()).stream().anyMatch(s -> s.value().equalsIgnoreCase(scanned)))
                .toList();
        if (matches.isEmpty()) {
            throw invalid("itemBarcode", "That item is not in " + task.getContainer().getLpnId());
        }
        String itemId = matches.getFirst().getItem().getItemId();
        ItemScan scan = ItemScan.of(matches.getFirst().getItem()).stream()
                .filter(s -> s.value().equalsIgnoreCase(scanned)).findFirst().orElseThrow();
        List<PackTaskDetail> wanted = matches.stream()
                .filter(d -> d.getOrder().getId().equals(carton.getOrder().getId()) && d.remaining() > 0).toList();
        if (wanted.isEmpty()) {
            throw invalid("itemBarcode", "No more " + itemId + " to pack for " + carton.getOrder().getOrderNumber());
        }
        wanted = chosen(wanted, request, itemId);

        long eaches = (long) request.quantity() * scan.eachesPerUnit();
        int left = wanted.stream().mapToInt(PackTaskDetail::remaining).sum();
        if (eaches > left) {
            throw invalid("quantity", "Too many: only " + left + " EA of " + itemId + " left to pack");
        }
        int toPack = (int) eaches;
        for (PackTaskDetail detail : wanted) {
            int take = Math.min(toPack, detail.remaining());
            if (take > 0) {
                InventoryRecord record = detail.getAllocation().getInventory();
                if (!record.isUsable()) {
                    throw ApiException.conflict((record.isExpired() ? "This stock has expired. "
                            : "This stock was put on hold after it was picked. ")
                            + "Set it aside and report it missing.", Map.of());
                }
                ledger.log(StockTransaction.Type.PACK, record, take, Place.of(record), Place.in(carton.getContainer()),
                        Note.by(userId, task.getTaskNumber()));
                move(detail, carton, take);
                toPack -= take;
            }
        }
        return saved(task);
    }

    /** Takes everything back out of an open carton and discards the carton. */
    public PackTaskView emptyCarton(UUID userId, UUID taskId, UUID cartonId) {
        PackTask task = active(userId, taskId);
        PackCarton carton = openCarton(task, cartonId);
        for (PackCartonLine line : carton.getLines()) {
            InventoryRecord record = line.getAllocation().getInventory();
            ledger.log(StockTransaction.Type.UNPACK, record, line.getQuantity(), Place.of(record), Place.in(task.getContainer()),
                    Note.by(userId, task.getTaskNumber()));
            moveBack(line, task.getContainer());
        }
        task.getCartons().remove(carton);
        tasks.flush();
        containers.delete(carton.getContainer());
        return finishIfDone(task);
    }

    /** Seals a carton. Closing the last one completes the task. */
    public PackTaskView closeCarton(UUID userId, UUID taskId, UUID cartonId, BigDecimal weight, WeightUom weightUom) {
        lockOrders(taskId);
        PackTask task = active(userId, taskId);
        PackCarton carton = openCarton(task, cartonId);
        if (carton.getLines().isEmpty()) {
            throw ApiException.conflict(carton.getContainer().getLpnId() + " is empty. Pack something or discard it.", Map.of());
        }
        close(carton, weight, weightUom, users.getReferenceById(userId));
        return finishIfDone(task);
    }

    /** Ships the container as it is: it is a carton and holds only one order. */
    public PackTaskView packAsIs(UUID userId, UUID taskId, BigDecimal weight, WeightUom weightUom) {
        lockOrders(taskId);
        PackTask task = active(userId, taskId);
        if (!canPackAsIs(task)) {
            throw ApiException.conflict(task.getContainer().getLpnId() + " can't ship as it is. Pack it into a carton.", Map.of());
        }
        PackCarton carton = new PackCarton(UUID.randomUUID(), task, task.orders().getFirst(), task.getContainer());
        for (PackTaskDetail detail : task.getDetails()) {
            int remaining = detail.remaining();
            if (remaining > 0) {
                if (!detail.getAllocation().getInventory().isUsable()) {
                    throw ApiException.conflict("Some of this stock is on hold or has expired. "
                            + "Set it aside and report it missing.", Map.of());
                }
                carton.addLine(detail, detail.getAllocation(), remaining);
                detail.setAllocation(null);
                detail.packed(remaining);
            }
        }
        task.getCartons().add(carton);
        close(carton, weight, weightUom, users.getReferenceById(userId));
        return finishIfDone(task);
    }

    /**
     * What is left of a detail is not in the container (or is damaged). It goes on hold at the
     * station to be looked into, and the order line needs that much stock again.
     */
    public PackTaskView reportMissing(UUID userId, UUID taskId, UUID detailId, Integer expectedRemaining) {
        lockOrders(taskId);
        PackTask task = active(userId, taskId);
        PackTaskDetail detail = task.getDetails().stream().filter(d -> d.getId().equals(detailId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Packing detail not found"));
        int missing = detail.remaining();
        if (missing == 0 || expectedRemaining == null || expectedRemaining != missing) {
            throw ApiException.conflict("This container has changed. Check what is left to pack.", Map.of());
        }
        Allocation allocation = detail.getAllocation();
        InventoryRecord record = allocation.getInventory();
        forget(detail, allocation);
        detail.missing(missing);
        detail.getLine().unpicked(missing);
        detail.getOrder().refreshStatus();
        tasks.flush();
        allocations.delete(allocation);
        InventoryStatus before = record.getStatus();
        Place from = Place.of(record);
        if (before == InventoryStatus.AVAILABLE) {
            record.hold(InventoryStatus.ON_HOLD, "Missing at packing, " + task.getTaskNumber());
        }
        ledger.log(StockTransaction.Type.HOLD, record, record.getQuantity(), from, Place.at(task.getStation(), null), before,
                record.getStatus(), Note.by(userId, task.getTaskNumber()).because("Missing at packing"));
        record.placeIn(task.getStation());
        events.save(new OrderEvent(detail.getOrder(), Type.SHORT,
                "Line %d: %d EA of %s missing at packing; put on hold at %s (%s)".formatted(detail.getLine().getLineNumber(),
                        missing, detail.getItem().getItemId(), task.getStation().getLocationId(), task.getTaskNumber())));
        return finishIfDone(task);
    }

    static boolean canPackAsIs(PackTask task) {
        return task.getStatus().isOpen() && !task.getContainer().isPermanent()
                && task.getContainer().getContainerType().getCategory() == ContainerCategory.CARTON
                && task.getCartons().isEmpty() && task.orders().size() == 1
                // What an order cancelled meanwhile left in it would go too.
                && task.getDetails().stream().allMatch(d -> d.getCancelledQuantity() == 0)
                && task.getDetails().stream().anyMatch(d -> d.remaining() > 0);
    }

    // ---------- Moving stock ----------

    /** Moves stock and its reservation from the container into a carton. */
    private void move(PackTaskDetail detail, PackCarton carton, int eaches) {
        Allocation source = detail.getAllocation();
        InventoryRecord from = source.getInventory();
        PackCartonLine line = carton.lineFor(detail).orElse(null);
        detail.packed(eaches);
        if (line == null && from.getQuantity() == eaches && source.getQuantity() == eaches) {
            // All of it: move the record as it is (a serial number can't be on two records).
            from.placeIn(carton.getContainer());
            carton.addLine(detail, source, eaches);
            detail.setAllocation(null);
            return;
        }
        if (line == null) {
            InventoryRecord moved = PickingService.copyOf(from, eaches);
            moved.placeIn(carton.getContainer());
            moved = inventory.save(moved);
            Allocation reservation = allocations.save(new Allocation(UUID.randomUUID(), detail.getLine(), moved, eaches));
            carton.addLine(detail, reservation, eaches);
        } else {
            line.add(eaches);
            line.getAllocation().changeQuantity(eaches);
            InventoryRecord packed = line.getAllocation().getInventory();
            packed.setQuantity(packed.getQuantity() + eaches);
        }
        if (source.getQuantity() == eaches) {
            forget(detail, source);
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

    /** Puts a carton line's stock back in the container it came from. */
    private void moveBack(PackCartonLine line, Container container) {
        PackTaskDetail detail = line.getDetail();
        Allocation packed = line.getAllocation();
        InventoryRecord record = packed.getInventory();
        detail.unpacked(line.getQuantity());
        line.clearAllocation();
        if (detail.getAllocation() == null) {
            record.placeIn(container);
            detail.setAllocation(packed);
            return;
        }
        InventoryRecord rest = detail.getAllocation().getInventory();
        detail.getAllocation().changeQuantity(line.getQuantity());
        rest.setQuantity(rest.getQuantity() + line.getQuantity());
        tasks.flush();
        allocations.delete(packed);
        allocations.flush();
        inventory.delete(record);
    }

    /** The reservation is about to go, so nothing may point at it. */
    private static void forget(PackTaskDetail detail, Allocation allocation) {
        detail.setAllocation(null);
        detail.getPickDetail().forgetPicked(allocation);
    }

    // ---------- Helpers ----------

    /** Narrows an item's details to the scanned serial number, or to one lot. */
    private static List<PackTaskDetail> chosen(List<PackTaskDetail> wanted, Pack request, String itemId) {
        if (wanted.stream().anyMatch(d -> d.getSerialNumber() != null)) {
            String serial = Codes.normalize(request.serialNumber());
            List<PackTaskDetail> match = wanted.stream()
                    .filter(d -> d.getSerialNumber() != null && d.getSerialNumber().equalsIgnoreCase(serial)).toList();
            if (match.isEmpty()) {
                throw invalid("serialNumber", serial == null ? "Scan the serial number" : serial + " is not to be packed here");
            }
            return match;
        }
        List<String> lots = wanted.stream().map(PackTaskDetail::getLotNumber).filter(Objects::nonNull).distinct().toList();
        String lot = Codes.normalize(request.lotNumber());
        if (lots.size() < 2 && lot == null) {
            return wanted;
        }
        List<PackTaskDetail> match = wanted.stream()
                .filter(d -> d.getLotNumber() != null && d.getLotNumber().equalsIgnoreCase(lot)).toList();
        if (match.isEmpty()) {
            throw invalid("lotNumber", lot == null ? "Choose the lot of " + itemId : "Lot " + lot + " is not to be packed here");
        }
        return match;
    }

    /** Others may be packing, or still picking, the same orders. */
    private void lockOrders(UUID taskId) {
        orders.findIdsOfPackTask(taskId).forEach(orders::findLocked);
    }

    private void close(PackCarton carton, BigDecimal weight, WeightUom weightUom, AppUser user) {
        if (weight != null) {
            if (weight.signum() <= 0 || weight.compareTo(MAX_WEIGHT) > 0 || weight.stripTrailingZeros().scale() > 3) {
                throw invalid("weight", "Enter a weight above 0, with up to 3 decimals");
            }
            if (weightUom == null) {
                throw invalid("weightUom", "Choose the weight unit");
            }
        }
        carton.close(weight, weightUom, user);
        carton.getLines().forEach(l -> l.getDetail().getLine().packed(l.getQuantity()));
        OutboundOrder order = carton.getOrder();
        order.refreshStatus();
        // A carton shipped as it is may be new in this call, so store it before the shipment points at it.
        tasks.flush();
        shipping.addCarton(carton);
        events.save(new OrderEvent(order, Type.PACKED, "%s packed %d EA into carton %s%s at %s (%s)".formatted(
                user.getDisplayName(), carton.totalQuantity(), carton.getContainer().getLpnId(),
                weight == null ? "" : ", " + weight.stripTrailingZeros().toPlainString() + " " + weightUom.name().toLowerCase(),
                carton.getTask().getStation().getLocationId(), carton.getTask().getTaskNumber())));
    }

    private PackTaskView finishIfDone(PackTask task) {
        if (task.isDone()) {
            task.complete();
            tasks.flush();
            boolean shipsAsIs = task.getCartons().stream()
                    .anyMatch(c -> c.getStatus() == CartonStatus.CLOSED && c.getContainer().getId().equals(task.getContainer().getId()));
            if (!shipsAsIs) {
                outbound.finish(task.getContainer());
            }
        }
        return saved(task);
    }

    private PackTaskView saved(PackTask task) {
        task.touch();
        tasks.flush();
        return PackTaskView.of(task);
    }

    private PackTask load(UUID id) {
        return tasks.findById(id).orElseThrow(() -> ApiException.notFound("Packing task not found"));
    }

    /** A task this user is packing. */
    private PackTask active(UUID userId, UUID taskId) {
        PackTask task = load(taskId);
        if (task.getStatus() == PackTaskStatus.COMPLETED) {
            throw ApiException.conflict(task.getTaskNumber() + " is already packed.", Map.of());
        }
        if (task.getStatus() == PackTaskStatus.CANCELLED) {
            throw ApiException.conflict(task.getTaskNumber() + " is called off: what is in " + task.getContainer().getLpnId()
                    + " goes back to storage.", Map.of());
        }
        if (task.getAssignedTo() == null) {
            throw ApiException.conflict("Scan " + task.getContainer().getLpnId() + " to start packing it.", Map.of());
        }
        if (!task.getAssignedTo().getId().equals(userId)) {
            throw ApiException.conflict(task.getTaskNumber() + " is being packed by "
                    + task.getAssignedTo().getDisplayName() + ".", Map.of("assignedTo", task.getAssignedTo().getDisplayName()));
        }
        return task;
    }

    private static PackCarton openCarton(PackTask task, UUID cartonId) {
        return task.getCartons().stream()
                .filter(c -> c.getId().equals(cartonId) && c.getStatus() == CartonStatus.OPEN).findFirst()
                .orElseThrow(() -> ApiException.conflict("That carton is not open.", Map.of()));
    }

    private Location station(String locationId) {
        String code = Codes.normalize(locationId);
        Location station = code == null ? null : locations.findByLocationId(code).orElse(null);
        if (station == null || station.getType() != LocationType.PACKING) {
            throw invalid("locationId", code == null ? "Scan a pack station" : code + " is not a pack station");
        }
        return station;
    }

    private static ApiException invalid(String field, String message) {
        return ApiException.invalid(message, Map.of(field, message));
    }
}
