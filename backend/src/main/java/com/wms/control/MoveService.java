package com.wms.control;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wms.common.Codes;
import com.wms.common.api.ApiException;
import com.wms.control.ControlViews.ItemRef;
import com.wms.control.ControlViews.MovableLine;
import com.wms.control.ControlViews.MoveResult;
import com.wms.control.ControlViews.MoveSource;
import com.wms.control.ControlViews.PutBack;
import com.wms.inventory.BusyLpns;
import com.wms.inventory.InventoryRecord;
import com.wms.inventory.InventoryRepository;
import com.wms.inventory.InventoryStatus;
import com.wms.inventory.LocationLocks;
import com.wms.inventory.LooseStock;
import com.wms.inventory.LpnLifecycle;
import com.wms.inventory.PlannedMoves;
import com.wms.inventory.StockLedger;
import com.wms.inventory.StockLedger.Place;
import com.wms.inventory.StockTransaction.Type;
import com.wms.masterdata.container.Container;
import com.wms.masterdata.container.ContainerCategory;
import com.wms.masterdata.container.ContainerRepository;
import com.wms.masterdata.container.ContainerStatus;
import com.wms.masterdata.item.ItemScan;
import com.wms.masterdata.location.Location;
import com.wms.masterdata.location.LocationRepository;
import com.wms.masterdata.location.LocationType;
import com.wms.putaway.StorageSpace;

/**
 * Moving stock from one storage location to another: a whole LPN, or some of what lies loose. Also back to storage from a
 * pack station, where things can be taken out of LPNs as well, as those hold what different orders were picked for.
 */
@Service
@Transactional
public class MoveService {

    private final InventoryRepository inventory;
    private final ContainerRepository containers;
    private final LocationRepository locations;
    private final StockQueryService stock;
    private final LocationLocks locks;
    private final StorageSpace space;
    private final StockLedger ledger;
    private final LooseStock loose;
    private final PlannedMoves moves;
    private final LpnLifecycle lifecycle;
    private final BusyLpns busy;

    MoveService(InventoryRepository inventory, ContainerRepository containers, LocationRepository locations, StockQueryService stock,
                LocationLocks locks, StorageSpace space, StockLedger ledger, LooseStock loose, PlannedMoves moves,
                LpnLifecycle lifecycle, BusyLpns busy) {
        this.lifecycle = lifecycle;
        this.busy = busy;
        this.inventory = inventory;
        this.containers = containers;
        this.locations = locations;
        this.stock = stock;
        this.locks = locks;
        this.space = space;
        this.ledger = ledger;
        this.loose = loose;
        this.moves = moves;
    }

    public record MoveLpn(String lpnId, String toLocationId, Boolean allowOver) {
    }

    /** @param requestId made by the device, so sending it again moves nothing more */
    public record MoveLoose(UUID requestId, UUID inventoryId, Integer quantity, String toLocationId, Boolean allowOver) {
    }

    /** Stock at pack stations that nothing has reserved. */
    @Transactional(readOnly = true)
    public List<PutBack> putBack() {
        return putBack(inventory.findAtType(LocationType.PACKING));
    }

    private List<PutBack> putBack(List<InventoryRecord> records) {
        Map<UUID, Integer> reserved = stock.reserved(records);
        Map<List<String>, List<InventoryRecord>> places = new LinkedHashMap<>();
        for (InventoryRecord r : records) {
            if (r.getQuantity() > reserved.getOrDefault(r.getId(), 0)) {
                Location at = StockLedger.locationOf(r);
                places.computeIfAbsent(Arrays.asList(at.getLocationId(), r.getContainer() == null ? null : r.getContainer().getLpnId()),
                        k -> new ArrayList<>()).add(r);
            }
        }
        return places.entrySet().stream().map(e -> new PutBack(e.getKey().get(0), e.getKey().get(1),
                e.getValue().stream().map(r -> r.getItem().getItemId()).distinct().sorted().toList(),
                e.getValue().stream().mapToInt(r -> r.getQuantity() - reserved.getOrDefault(r.getId(), 0)).sum(),
                e.getValue().stream().filter(r -> r.getStatus() != InventoryStatus.AVAILABLE)
                        .mapToInt(r -> r.getQuantity() - reserved.getOrDefault(r.getId(), 0)).sum())).toList();
    }

    /** An LPN in storage or at a pack station, or such a location with loose stock. */
    @Transactional(readOnly = true)
    public MoveSource source(String scanned) {
        String code = Codes.normalize(scanned);
        if (code == null) {
            throw invalid("code", "Scan an LPN or a location");
        }
        Container lpn = containers.findByLpnId(code).orElse(null);
        if (lpn != null) {
            Location at = checkLpn(lpn);
            List<InventoryRecord> contents = space.contents(lpn);
            if (at.getType() == LocationType.STORAGE) {
                return new MoveSource(code, lpn.getContainerType().getDescription(), at.getLocationId(), true, false, null, lines(contents), List.of());
            }
            List<MovableLine> free = lines(contents.stream().filter(r -> r.getContainer().getId().equals(lpn.getId())).toList());
            if (free.stream().allMatch(l -> l.free() == 0)) {
                throw invalid("code", "Everything in " + code + " is reserved for orders, so it stays at " + at.getLocationId());
            }
            String whyStays = busy.busyWith(lpn.getId()).map(b -> code + " " + b).orElse(
                    free.stream().anyMatch(l -> l.free() < l.quantity()) ? "Some of " + code + " is reserved" : null);
            return new MoveSource(code, lpn.getContainerType().getDescription(), at.getLocationId(), whyStays == null, true, whyStays,
                    free.stream().filter(l -> l.free() > 0).toList(), List.of());
        }
        Location at = locations.findByLocationId(code).orElse(null);
        if (at == null) {
            throw invalid("code", code + " is not a known LPN or location");
        }
        checkFrom(at);
        List<InventoryRecord> loose = inventory.findByLocation_IdIn(List.of(at.getId()));
        List<PutBack> lpns = at.getType() != LocationType.PACKING ? List.of()
                : putBack(inventory.findAtType(LocationType.PACKING).stream()
                        .filter(r -> r.getContainer() != null && StockLedger.locationOf(r).getId().equals(at.getId())).toList());
        if (loose.isEmpty() && lpns.isEmpty()) {
            throw invalid("code", at.getType() == LocationType.PACKING ? "There is nothing to put back at " + code
                    : code + " holds nothing loose. Scan the LPN to move");
        }
        return new MoveSource(null, null, code, false, false, null, lines(loose), lpns);
    }

    public MoveResult lpn(Actor actor, MoveLpn request) {
        space.lock();
        String code = Codes.normalize(request.lpnId());
        Container lpn = code == null ? null : containers.findLockedByLpnId(code).orElse(null);
        if (lpn == null) {
            throw invalid("lpnId", code == null ? "Scan an LPN" : code + " is not a known LPN");
        }
        Location to = destination(request.toLocationId());
        MoveResult done = new MoveResult(code + " is at " + to.getLocationId());
        if (lpn.getCurrentLocation() != null && lpn.getCurrentLocation().getId().equals(to.getId())) {
            return done; // sent twice, or there already
        }
        checkLpn(lpn);
        busy.busyWith(lpn.getId()).ifPresent(b -> {
            throw ApiException.conflict(code + " " + b + ", so it has to stay.", Map.of());
        });
        List<InventoryRecord> contents = space.contents(lpn);
        if (!contents.isEmpty()) {
            contents = inventory.findLocked(contents.stream().map(InventoryRecord::getId).toList());
        }
        moves.callOff(contents.stream().map(InventoryRecord::getId).toList(), "The LPN was moved");
        int reserved = stock.reserved(contents).values().stream().mapToInt(Integer::intValue).sum();
        if (reserved > 0) {
            throw ApiException.conflict(reserved + " EA in " + code + " are reserved for orders or replenishment, so it has to stay.", Map.of());
        }
        checkRoom(space.forLpn(lpn, to), request.allowOver());
        Location from = lpn.getCurrentLocation();
        for (InventoryRecord r : contents) {
            ledger.log(Type.MOVE, r, r.getQuantity(), Place.at(from, r.getContainer()), Place.at(to, r.getContainer()), actor.note(null));
        }
        lpn.setCurrentLocation(to);
        return done;
    }

    public MoveResult loose(Actor actor, MoveLoose request) {
        if (request.requestId() == null) {
            throw invalid("requestId", "A request ID is required");
        }
        space.lock();
        Location to = destination(request.toLocationId());
        if (ledger.has(request.requestId())) {
            return new MoveResult("Moved to " + to.getLocationId());
        }
        InventoryRecord r = request.inventoryId() == null ? null
                : inventory.findLocked(List.of(request.inventoryId())).stream().findFirst().orElse(null);
        Location from = r == null ? null : StockLedger.locationOf(r);
        // Out of an LPN only at a pack station; in storage an LPN is moved whole.
        if (from == null || r.getLocation() == null && from.getType() != LocationType.PACKING) {
            throw ApiException.conflict("That stock is no longer there. Scan " + (r == null || r.getLocation() != null ? "the location" : "it")
                    + " again.", Map.of());
        }
        Container out = r.getContainer();
        checkFrom(from);
        if (from.getId().equals(to.getId())) {
            throw invalid("toLocationId", "It is already at " + to.getLocationId());
        }
        int quantity = request.quantity() == null ? 0 : request.quantity();
        int free = r.getQuantity() - stock.reserved(List.of(r)).getOrDefault(r.getId(), 0);
        if (quantity > free) {
            moves.callOff(List.of(r.getId()), "The stock was moved");
            free = r.getQuantity() - stock.reserved(List.of(r)).getOrDefault(r.getId(), 0);
        }
        if (quantity < 1) {
            throw invalid("quantity", "Enter the quantity to move");
        }
        if (quantity > free) {
            throw invalid("quantity", free == r.getQuantity() ? "There are only " + free + " EA"
                    : "Only " + free + " EA can be moved; the rest is reserved");
        }
        InventoryRecord moving = quantity == r.getQuantity() ? r : r.copy(quantity);
        checkRoom(space.forLoose(moving, to), request.allowOver());
        ledger.log(Type.MOVE, r, quantity, Place.at(from, out), Place.at(to, null), actor.note(null).request(request.requestId()));
        loose.put(r, quantity, to);
        if (out != null && busy.busyWith(out.getId()).isEmpty()) {
            lifecycle.retireIfEmptied(out);
        }
        return new MoveResult("Moved %,d EA of %s to %s".formatted(quantity, r.getItem().getItemId(), to.getLocationId()));
    }

    private List<MovableLine> lines(List<InventoryRecord> records) {
        Map<UUID, Integer> reserved = stock.reserved(records);
        return records.stream().map(r -> new MovableLine(r.getId(), ItemRef.of(r.getItem()), ItemScan.of(r.getItem()), r.getLotNumber(),
                r.getSerialNumber(), r.getExpiryDate(), r.getStatus(), r.getQuantity(),
                r.getQuantity() - reserved.getOrDefault(r.getId(), 0))).toList();
    }

    /** Where an LPN that may be moved is. */
    private Location checkLpn(Container lpn) {
        if (lpn.getParent() != null) {
            throw invalid("lpnId", lpn.getLpnId() + " is inside " + lpn.getParent().getLpnId() + ". Scan that instead");
        }
        if (lpn.getContainerType().getCategory() == ContainerCategory.BIN) {
            throw invalid("lpnId", lpn.getLpnId() + " is a storage bin and stays where it is");
        }
        Location at = lpn.getCurrentLocation();
        if (at != null && at.getType().isForReceiving()) {
            throw invalid("lpnId", lpn.getLpnId() + " is at " + at.getLocationId() + ". Put it away instead");
        }
        if (at == null || !movableFrom(at) || lpn.getStatus() != ContainerStatus.IN_USE) {
            throw invalid("lpnId", lpn.getLpnId() + " is not in storage or at a pack station");
        }
        checkFrom(at);
        return at;
    }

    private void checkFrom(Location at) {
        if (!movableFrom(at)) {
            throw invalid("code", at.getLocationId() + " is not a storage location or pack station");
        }
        if (locks.isLocked(at.getId())) {
            throw ApiException.conflict(at.getLocationId() + " is being counted.", Map.of());
        }
    }

    private static boolean movableFrom(Location at) {
        return at.getType() == LocationType.STORAGE || at.getType() == LocationType.PACKING;
    }

    private Location destination(String scanned) {
        String code = Codes.normalize(scanned);
        Location to = code == null ? null : locations.findByLocationId(code).orElse(null);
        if (to == null || to.getType() != LocationType.STORAGE) {
            throw invalid("toLocationId", code == null ? "Scan a location" : code + " is not a storage location");
        }
        if (locks.isLocked(to.getId())) {
            throw invalid("toLocationId", code + " is being counted. Choose another location");
        }
        return to;
    }

    private static void checkRoom(StorageSpace.Check room, Boolean allowOver) {
        if (room.refused() != null) {
            throw invalid("toLocationId", room.refused());
        }
        if (room.over() != null && !Boolean.TRUE.equals(allowOver)) {
            throw ApiException.conflict(room.over() + ".", Map.of("capacity", room.over()));
        }
    }

    private static ApiException invalid(String field, String message) {
        return ApiException.invalid(message, Map.of(field, message));
    }
}
