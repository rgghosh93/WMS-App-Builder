package com.wms.control;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wms.common.Codes;
import com.wms.common.api.ApiException;
import com.wms.control.ControlViews.ItemRef;
import com.wms.control.CountTask.Status;
import com.wms.control.CountViews.CountDetail;
import com.wms.control.CountViews.CountItem;
import com.wms.control.CountViews.CountState;
import com.wms.control.CountViews.CountSummary;
import com.wms.control.CountViews.Counting;
import com.wms.control.CountViews.EntryView;
import com.wms.control.CountViews.Planned;
import com.wms.control.CountViews.ResultView;
import com.wms.control.CountViews.Waiting;
import com.wms.inventory.InventoryRecord;
import com.wms.inventory.InventoryRepository;
import com.wms.inventory.InventoryStatus;
import com.wms.inventory.PlannedMoves;
import com.wms.inventory.StockLedger;
import com.wms.inventory.StockReservations;
import com.wms.inventory.StockReservations.Released;
import com.wms.inventory.StockTransaction.Type;
import com.wms.masterdata.container.Container;
import com.wms.masterdata.container.ContainerRepository;
import com.wms.masterdata.container.ContainerStatus;
import com.wms.masterdata.item.Item;
import com.wms.masterdata.item.ItemRepository;
import com.wms.masterdata.item.ItemScan;
import com.wms.masterdata.item.ItemFinder;
import com.wms.masterdata.location.Location;
import com.wms.masterdata.location.LocationRepository;
import com.wms.masterdata.location.LocationType;
import com.wms.masterdata.zone.Zone;
import com.wms.masterdata.zone.ZoneFunction;
import com.wms.masterdata.zone.Zones;
import com.wms.putaway.StorageSpace;
import com.wms.work.Directions;
import com.wms.work.Directions.Candidate;

/**
 * Cycle counts. People count without seeing what is expected. Small differences are put right at
 * once; larger ones wait for someone else to approve them or have the location counted again.
 */
@Service
@Transactional
public class CountService {

    private static final int MAX_PLANNED = 500;
    private static final int MAX_EACHES = 1_000_000;
    private static final int SHOW_WAITING = 20;
    private static final List<Status> WAITING = List.of(Status.OPEN, Status.RECOUNT);

    private final CountTaskRepository counts;
    private final InventoryRepository inventory;
    private final ContainerRepository containers;
    private final LocationRepository locations;
    private final ItemRepository items;
    private final ItemFinder finder;
    private final StockQueryService stock;
    private final AdjustmentService adjustments;
    private final StockReservations reservations;
    private final PlannedMoves moves;
    private final Directions directions;
    private final StorageSpace space;
    private final ReasonService reasons;
    private final Zones zones;

    CountService(CountTaskRepository counts, InventoryRepository inventory, ContainerRepository containers, LocationRepository locations,
                 ItemRepository items, ItemFinder finder, StockQueryService stock, AdjustmentService adjustments,
                 StockReservations reservations, PlannedMoves moves, StorageSpace space, ReasonService reasons, Zones zones,
                 Directions directions) {
        this.counts = counts;
        this.inventory = inventory;
        this.containers = containers;
        this.locations = locations;
        this.items = items;
        this.finder = finder;
        this.stock = stock;
        this.adjustments = adjustments;
        this.reservations = reservations;
        this.moves = moves;
        this.directions = directions;
        this.space = space;
        this.reasons = reasons;
        this.zones = zones;
    }

    /**
     * What to count: a location, everywhere an item is, a counting zone, or an address zone of an area. Empty
     * locations of a zone only if asked.
     */
    public record Plan(String locationId, String area, String zone, UUID zoneId, String itemId, String owner, Boolean includeEmpty) {
    }

    /**
     * @param requestId made by the device; sending it again counts nothing more
     * @param owner     code of whose item it is, if several have what was scanned
     */
    public record Entry(UUID requestId, String lpnId, String itemBarcode, String owner, Integer quantity, String lotNumber, List<String> serialNumbers) {
    }

    // ---------- On the desktop ----------

    @Transactional(readOnly = true)
    public Page<CountSummary> search(String q, Status status, Pageable page) {
        String like = q == null || q.isBlank() ? "%" : "%" + q.trim().toLowerCase(Locale.ROOT).replace("\\", "\\\\")
                .replace("%", "\\%").replace("_", "\\_") + "%";
        return counts.search(like, status, page).map(CountSummary::of);
    }

    @Transactional(readOnly = true)
    public CountDetail get(UUID id) {
        return detail(counts.findById(id).orElseThrow(() -> ApiException.notFound("Count not found")));
    }

    public Planned plan(Actor actor, Plan plan) {
        List<Location> wanted = wanted(plan);
        Set<UUID> busy = wanted.isEmpty() ? Set.of() : counts.findLocations(wanted.stream().map(Location::getId).toList(), CountTask.ACTIVE);
        List<Location> chosen = wanted.stream().filter(l -> l.getType() == LocationType.STORAGE && !busy.contains(l.getId())).toList();
        if (chosen.isEmpty()) {
            throw ApiException.conflict(wanted.isEmpty() ? "There is nothing there to count." : "Counts are already planned there.", Map.of());
        }
        if (chosen.size() > MAX_PLANNED) {
            throw ApiException.invalid("That is " + chosen.size() + " locations. Plan at most " + MAX_PLANNED + " at a time.",
                    Map.of("zone", "at most " + MAX_PLANNED + " locations"));
        }
        chosen.forEach(l -> counts.save(new CountTask("CC%06d".formatted(counts.nextNumber()), l, actor)));
        counts.flush();
        return new Planned(chosen.size(), wanted.size() - chosen.size());
    }

    private List<Location> wanted(Plan plan) {
        String code = Codes.normalize(plan.locationId());
        if (code != null) {
            Location l = locations.findByLocationId(code).orElseThrow(() -> invalid("locationId", code + " is not a known location"));
            if (l.getType() != LocationType.STORAGE) {
                throw invalid("locationId", code + " is not a storage location");
            }
            return List.of(l);
        }
        String itemCode = Codes.normalize(plan.itemId());
        if (itemCode != null) {
            Item item = finder.byId(itemCode, plan.owner(), "itemId");
            return inventory.findByItem_Id(item.getId()).stream().map(StockLedger::locationOf)
                    .filter(l -> l != null && l.getType() == LocationType.STORAGE).distinct().toList();
        }
        List<Location> all;
        if (plan.zoneId() != null) {
            Zone counting = zones.require(plan.zoneId(), ZoneFunction.COUNTING, "zoneId");
            all = zones.locations(counting);
            if (all.isEmpty()) {
                throw invalid("zoneId", counting.getCode() + " has no locations");
            }
        } else {
            String area = Codes.normalize(plan.area());
            String zone = Codes.normalize(plan.zone());
            if (area == null || zone == null) {
                throw invalid(area == null ? "area" : "zone", "Choose what to count");
            }
            all = locations.findByTypeAndAreaAndZoneOrderByLocationId(LocationType.STORAGE, area, zone);
            if (all.isEmpty()) {
                throw invalid("zone", "No storage locations in zone " + zone + " of area " + area);
            }
        }
        if (Boolean.TRUE.equals(plan.includeEmpty())) {
            return all;
        }
        Set<UUID> ids = new HashSet<>();
        all.forEach(l -> ids.add(l.getId()));
        Set<UUID> held = new HashSet<>();
        inventory.findByLocation_IdIn(ids).forEach(r -> held.add(r.getLocation().getId()));
        containers.findByCurrentLocation_IdIn(ids).forEach(c -> held.add(c.getCurrentLocation().getId()));
        return all.stream().filter(l -> held.contains(l.getId())).toList();
    }

    public CountDetail approve(Actor actor, UUID id) {
        CountTask task = inReview(id);
        if (actor.id().equals(task.getCounterId())) {
            throw ApiException.conflict("Someone else has to approve what you counted.", Map.of());
        }
        // Settled first, so the location is open again when orders look for other stock.
        task.complete(actor);
        counts.flush();
        putRight(task, actor);
        return detail(task);
    }

    /** Counted again from scratch; the location stays locked. */
    public CountDetail recount(Actor actor, UUID id) {
        CountTask task = inReview(id);
        task.recount(actor);
        counts.flush();
        return detail(task);
    }

    /** Leaves the stock as it was. */
    public CountDetail cancel(Actor actor, UUID id) {
        CountTask task = lock(id);
        if (!CountTask.ACTIVE.contains(task.getStatus())) {
            throw ApiException.conflict(task.getNumber() + " is already " + (task.getStatus() == Status.CANCELLED ? "cancelled." : "done."), Map.of());
        }
        task.cancel(actor);
        counts.flush();
        return detail(task);
    }

    // ---------- On a device ----------

    @Transactional(readOnly = true)
    public CountState state(UUID userId) {
        return state(userId, null);
    }

    private CountState state(UUID userId, String message) {
        CountTask mine = counts.findFirstByCounterIdAndStatus(userId, Status.COUNTING).orElse(null);
        Counting counting = mine == null ? null : new Counting(mine.getId(), mine.getNumber(), mine.getLocation().getLocationId(),
                mine.getEntries().stream().map(EntryView::of).toList());
        List<CountTask> all = counts.findWaiting(WAITING);
        Map<UUID, CountTask> byId = new HashMap<>();
        all.forEach(t -> byId.put(t.getId(), t));
        List<UUID> forMe = directions.arrange(userId, all.stream().map(t -> new Candidate(t.getId(), CountWork.own(directions, t))).toList());
        return new CountState(counting, forMe.size(), counting != null ? List.of() : forMe.stream().limit(SHOW_WAITING).map(byId::get)
                .map(t -> new Waiting(t.getNumber(), t.getLocation().getLocationId(), t.getStatus() == Status.RECOUNT)).toList(), message);
    }

    /** @param takeOver if someone else is counting there, carry on with it */
    public CountState start(Actor actor, String scanned, boolean takeOver) {
        String code = Codes.normalize(scanned);
        Location at = code == null ? null : locations.findByLocationId(code).orElse(null);
        if (at == null) {
            throw invalid("locationId", code == null ? "Scan a location" : code + " is not a known location");
        }
        CountTask mine = counts.findFirstByCounterIdAndStatus(actor.id(), Status.COUNTING).orElse(null);
        if (mine != null && !mine.getLocation().getId().equals(at.getId())) {
            throw ApiException.conflict("Finish counting " + mine.getLocation().getLocationId() + " first.", Map.of());
        }
        CountTask found = counts.findFirstByLocation_IdAndStatusIn(at.getId(), CountTask.ACTIVE).orElse(null);
        CountTask task = found == null ? null : lock(found.getId());
        if (task == null || !CountTask.ACTIVE.contains(task.getStatus())) {
            throw invalid("locationId", "No count is planned at " + code);
        }
        if (task.getStatus() == Status.REVIEW) {
            throw invalid("locationId", code + " is counted and waiting to be reviewed");
        }
        if (task.getStatus() == Status.COUNTING) {
            if (!actor.id().equals(task.getCounterId())) {
                if (!takeOver) {
                    throw ApiException.conflict(task.getCounter() + " is counting " + code + ".", Map.of("assignedTo", task.getCounter()));
                }
                task.assign(actor);
                counts.flush();
            }
            return state(actor.id());
        }
        if (!takeOver) {
            directions.assignedToOther(task.getId(), actor.id()).ifPresent(other -> {
                throw ApiException.conflict("Counting " + code + " is assigned to " + other + ".", Map.of("assignedTo", other));
            });
        }
        directions.starting(actor.id());
        task.start(actor);
        counts.flush(); // locked from here on, so no pick can start there
        reservations.beingPicked(at.getId()).or(() -> moves.beingMoved(at.getId())).ifPresent(why -> {
            throw ApiException.conflict("Count " + code + " later: " + why + ".", Map.of());
        });
        return state(actor.id());
    }

    /** What a scanned item is. It doesn't have to be expected here. */
    @Transactional(readOnly = true)
    public CountItem item(String scanned, String owner) {
        return found(scanned, owner);
    }

    public CountState enter(Actor actor, UUID id, Entry entry) {
        CountTask task = active(actor, id);
        if (entry.requestId() == null) {
            throw invalid("requestId", "A request ID is required");
        }
        if (task.getEntries().stream().anyMatch(e -> e.getRequestId().equals(entry.requestId()))) {
            return state(actor.id());
        }
        CountItem scanned = found(entry.itemBarcode(), entry.owner());
        Item item = items.findById(scanned.item().id()).orElseThrow();
        Container lpn = lpnHere(task, entry.lpnId());
        String lot = null;
        if (item.isLotControlled()) {
            lot = Codes.normalize(entry.lotNumber());
            if (lot == null || lot.length() > 40) {
                throw invalid("lotNumber", "Enter the lot of " + item.getItemId());
            }
        }
        if (item.isSerialControlled()) {
            List<String> serials = entry.serialNumbers() == null ? List.of()
                    : entry.serialNumbers().stream().map(Codes::normalize).filter(Objects::nonNull).toList();
            if (serials.isEmpty()) {
                throw invalid("serialNumbers", "Scan the serial numbers of " + item.getItemId());
            }
            int part = 0;
            for (String serial : serials) {
                boolean twice = task.getEntries().stream().anyMatch(e -> e.getItem().getId().equals(item.getId()) && serial.equals(e.getSerialNumber()));
                if (twice || serial.length() > 60) {
                    throw invalid("serialNumbers", twice ? serial + " is already counted" : "At most 60 characters");
                }
                inventory.findByItem_IdAndSerialNumber(item.getId(), serial).map(StockLedger::locationOf)
                        .filter(l -> !l.getId().equals(task.getLocation().getId())).ifPresent(l -> {
                            throw invalid("serialNumbers", serial + " is recorded at " + l.getLocationId() + ". Set it aside and tell a supervisor");
                        });
                task.getEntries().add(new CountEntry(task, entry.requestId(), part++, lpn, item, lot, serial, 1));
            }
        } else {
            long eaches = entry.quantity() == null ? 0 : (long) entry.quantity() * scanned.scan().eachesPerUnit();
            if (eaches < 1 || eaches > MAX_EACHES) {
                throw invalid("quantity", "Enter the quantity counted");
            }
            task.getEntries().add(new CountEntry(task, entry.requestId(), 0, lpn, item, lot, null, (int) eaches));
        }
        counts.flush();
        return state(actor.id());
    }

    /** A mistake: it is counted again. */
    public CountState removeEntry(Actor actor, UUID id, UUID entryId) {
        CountTask task = active(actor, id);
        task.getEntries().removeIf(e -> e.getId().equals(entryId));
        counts.flush();
        return state(actor.id());
    }

    public CountState giveBack(Actor actor, UUID id) {
        CountTask task = active(actor, id);
        task.giveBack();
        counts.flush();
        return state(actor.id(), "Given back");
    }

    /** Everything there is counted. */
    public CountState finish(Actor actor, UUID id) {
        CountTask task = active(actor, id);
        compare(task);
        int worst = task.getResults().stream().mapToInt(r -> Math.abs(r.difference())).max().orElse(0);
        int all = task.getResults().stream().mapToInt(r -> Math.abs(r.difference())).sum();
        String where = task.getLocation().getLocationId();
        String message;
        if (worst == 0) {
            task.counted(Status.COMPLETED);
            message = where + " is counted.";
        } else if (all <= reasons.approvalLimit()) {
            task.counted(Status.COMPLETED);
            putRight(task, null);
            message = where + " is counted, and small differences are put right.";
        } else {
            task.counted(Status.REVIEW);
            message = where + " is counted. A supervisor will review it.";
        }
        counts.flush();
        return state(actor.id(), message);
    }

    // ---------- Comparing and putting right ----------

    /** The same stock as far as counting goes: people don't see status, expiry or which record it is. */
    private record Key(UUID containerId, UUID itemId, String lot, String serial) {
    }

    private void compare(CountTask task) {
        Map<Key, int[]> totals = new LinkedHashMap<>();
        Map<Key, Container> lpns = new HashMap<>();
        Map<Key, Item> of = new HashMap<>();
        for (InventoryRecord r : here(task)) {
            Key key = key(r);
            totals.computeIfAbsent(key, k -> new int[2])[0] += r.getQuantity();
            lpns.put(key, r.getContainer());
            of.put(key, r.getItem());
        }
        for (CountEntry e : task.getEntries()) {
            Key key = new Key(e.getContainer() == null ? null : e.getContainer().getId(), e.getItem().getId(), e.getLotNumber(), e.getSerialNumber());
            totals.computeIfAbsent(key, k -> new int[2])[1] += e.getQuantity();
            lpns.put(key, e.getContainer());
            of.put(key, e.getItem());
        }
        task.getResults().clear();
        counts.flush();
        totals.forEach((key, n) -> task.getResults().add(new CountResult(task, lpns.get(key), of.get(key), key.lot(), key.serial(), n[0], n[1])));
    }

    /** Makes the stock what was counted. Orders that lose stock get other stock if there is any. */
    private void putRight(CountTask task, Actor approver) {
        List<InventoryRecord> all = here(task);
        List<InventoryRecord> locked = all.isEmpty() ? all : inventory.findLocked(all.stream().map(InventoryRecord::getId).toList());
        Map<Key, List<InventoryRecord>> byKey = new HashMap<>();
        locked.forEach(r -> byKey.computeIfAbsent(key(r), k -> new ArrayList<>()).add(r));
        Map<UUID, Integer> reserved = stock.reserved(locked);
        StockLedger.Note note = StockLedger.Note.by(task.getCounterId(), task.getCounter(), task.getNumber())
                .because(approver == null ? "Cycle count" : "Cycle count (approved by " + approver.name() + ")");

        Map<InventoryRecord, Integer> changes = new LinkedHashMap<>();
        Map<UUID, Integer> keep = new HashMap<>();
        for (CountResult result : task.getResults()) {
            List<InventoryRecord> records = byKey.getOrDefault(
                    new Key(result.getContainerId(), result.getItem().getId(), result.getLotNumber(), result.getSerialNumber()), List.of());
            int now = records.stream().mapToInt(InventoryRecord::getQuantity).sum();
            if (now != result.getExpected()) {
                throw ApiException.conflict("The stock at " + task.getLocation().getLocationId() + " has changed since. Count it again.", Map.of());
            }
            int missing = -result.difference();
            // What no order needs goes first, then good stock, so reservations stay while they can.
            for (InventoryRecord r : records.stream().sorted(Comparator.comparingInt((InventoryRecord r) -> reserved.getOrDefault(r.getId(), 0) > 0 ? 1 : 0)
                    .thenComparing(r -> r.getStatus() == InventoryStatus.AVAILABLE ? 0 : 1)).toList()) {
                if (missing <= 0) {
                    break;
                }
                int take = Math.min(missing, r.getQuantity());
                changes.put(r, -take);
                keep.put(r.getId(), r.getQuantity() - take);
                missing -= take;
            }
            if (result.difference() > 0) {
                InventoryRecord to = records.stream().filter(r -> r.getStatus() == InventoryStatus.AVAILABLE).findFirst()
                        .orElse(records.isEmpty() ? null : records.getFirst());
                if (to != null) {
                    changes.put(to, result.difference());
                } else {
                    found(task, result, note);
                }
            }
        }
        // Moves nobody has started are planned again later, if they are still needed.
        moves.callOff(keep.keySet(), "Not found when " + task.getLocation().getLocationId() + " was counted");
        Released released = reservations.release(keep, "not found when " + task.getLocation().getLocationId() + " was counted");
        Map<UUID, Integer> still = stock.reserved(changes.keySet());
        changes.forEach((r, change) -> {
            if (r.getQuantity() + change < still.getOrDefault(r.getId(), 0)) {
                throw ApiException.conflict("Stock at " + task.getLocation().getLocationId() + " is being picked. Try again when that is done.", Map.of());
            }
            adjustments.changeQuantity(r, change, Type.COUNT, note);
        });
        inventory.flush();
        reservations.reallocate(released);
    }

    /** Stock counted where none was recorded. */
    private void found(CountTask task, CountResult result, StockLedger.Note note) {
        Item item = result.getItem();
        if (result.getSerialNumber() != null && inventory.existsByItem_IdAndSerialNumber(item.getId(), result.getSerialNumber())) {
            throw ApiException.conflict(result.getSerialNumber() + " is recorded somewhere else. Count it again.", Map.of());
        }
        InventoryRecord r = new InventoryRecord(UUID.randomUUID(), item, result.difference());
        r.setLotNumber(result.getLotNumber());
        r.setSerialNumber(result.getSerialNumber());
        if (item.isExpiryControlled()) {
            // The same lot has the same date; without one nobody should use it yet.
            r.setExpiryDate(result.getLotNumber() == null ? null : inventory.findByItem_IdAndLotNumber(item.getId(), result.getLotNumber()).stream()
                    .map(InventoryRecord::getExpiryDate).filter(Objects::nonNull).findFirst().orElse(null));
            if (r.getExpiryDate() == null) {
                r.hold(InventoryStatus.ON_HOLD, "Found in " + task.getNumber() + "; expiry date not known");
            }
        }
        if (result.getContainerId() != null) {
            r.placeIn(containers.findById(result.getContainerId()).orElseThrow());
        } else {
            r.placeIn(task.getLocation());
        }
        adjustments.addNew(r, Type.COUNT, note);
    }

    private List<InventoryRecord> here(CountTask task) {
        UUID at = task.getLocation().getId();
        List<InventoryRecord> all = new ArrayList<>(inventory.findByLocation_IdIn(List.of(at)));
        containers.findByCurrentLocation_IdIn(List.of(at)).forEach(c -> all.addAll(space.contents(c)));
        return all;
    }

    private static Key key(InventoryRecord r) {
        return new Key(r.getContainer() == null ? null : r.getContainer().getId(), r.getItem().getId(), r.getLotNumber(), r.getSerialNumber());
    }

    // ---------- Checks ----------

    private CountItem found(String scanned, String owner) {
        Item item = finder.scanned(scanned, owner, "itemBarcode");
        String code = Codes.normalize(scanned);
        ItemScan scan = ItemScan.of(item).stream().filter(s -> s.value().equalsIgnoreCase(code)).findFirst()
                .orElseGet(() -> new ItemScan(item.getItemId(), "EA", 1));
        return new CountItem(ItemRef.of(item), scan);
    }

    private Container lpnHere(CountTask task, String scanned) {
        String code = Codes.normalize(scanned);
        if (code == null) {
            return null;
        }
        Container lpn = containers.findByLpnId(code).orElse(null);
        Location at = lpn == null ? null : StockLedger.locationOf(lpn);
        if (at == null || !at.getId().equals(task.getLocation().getId()) || lpn.getStatus() == ContainerStatus.RETIRED) {
            throw invalid("lpnId", code + " is not recorded here. Set it aside and tell a supervisor");
        }
        return lpn;
    }

    private CountTask lock(UUID id) {
        return counts.findLocked(id).orElseThrow(() -> ApiException.notFound("Count not found"));
    }

    private CountTask inReview(UUID id) {
        CountTask task = lock(id);
        if (task.getStatus() != Status.REVIEW) {
            throw ApiException.conflict(task.getNumber() + " is not waiting to be reviewed.", Map.of());
        }
        return task;
    }

    private CountTask active(Actor actor, UUID id) {
        CountTask task = lock(id);
        if (task.getStatus() != Status.COUNTING || !actor.id().equals(task.getCounterId())) {
            throw ApiException.conflict("You are no longer counting " + task.getLocation().getLocationId() + ".", Map.of());
        }
        return task;
    }

    private static CountDetail detail(CountTask t) {
        return new CountDetail(CountSummary.of(t), t.getResults().stream().map(ResultView::of)
                .sorted(Comparator.comparing((ResultView r) -> r.difference() == 0).thenComparing(r -> r.item().itemId())
                        .thenComparing(ResultView::lpn, Comparator.nullsFirst(Comparator.naturalOrder()))).toList());
    }

    private static ApiException invalid(String field, String message) {
        return ApiException.invalid(message, Map.of(field, message));
    }
}
