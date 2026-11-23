package com.wms.control;

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
import com.wms.inventory.InventoryRecord;
import com.wms.inventory.InventoryRepository;
import com.wms.inventory.LocationLocks;
import com.wms.inventory.LooseStock;
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

/** Moving stock from one storage location to another: a whole LPN, or some of what lies loose. */
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

    MoveService(InventoryRepository inventory, ContainerRepository containers, LocationRepository locations, StockQueryService stock,
                LocationLocks locks, StorageSpace space, StockLedger ledger, LooseStock loose, PlannedMoves moves) {
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

    /** An LPN in storage, or a storage location with loose stock. */
    @Transactional(readOnly = true)
    public MoveSource source(String scanned) {
        String code = Codes.normalize(scanned);
        if (code == null) {
            throw invalid("code", "Scan an LPN or a location");
        }
        Container lpn = containers.findByLpnId(code).orElse(null);
        if (lpn != null) {
            Location at = checkLpn(lpn);
            return new MoveSource(code, lpn.getContainerType().getDescription(), at.getLocationId(), lines(space.contents(lpn)));
        }
        Location at = locations.findByLocationId(code).orElse(null);
        if (at == null) {
            throw invalid("code", code + " is not a known LPN or location");
        }
        checkFrom(at);
        List<InventoryRecord> loose = inventory.findByLocation_IdIn(List.of(at.getId()));
        if (loose.isEmpty()) {
            throw invalid("code", code + " holds nothing loose. Scan the LPN to move");
        }
        return new MoveSource(null, null, code, lines(loose));
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
        if (r == null || r.getLocation() == null) {
            throw ApiException.conflict("That stock is no longer there. Scan the location again.", Map.of());
        }
        Location from = r.getLocation();
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
        ledger.log(Type.MOVE, r, quantity, Place.at(from, null), Place.at(to, null), actor.note(null).request(request.requestId()));
        loose.put(r, quantity, to);
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
        if (at == null || at.getType() != LocationType.STORAGE || lpn.getStatus() != ContainerStatus.IN_USE) {
            throw invalid("lpnId", lpn.getLpnId() + " is not in storage");
        }
        checkFrom(at);
        return at;
    }

    private void checkFrom(Location at) {
        if (at.getType() != LocationType.STORAGE) {
            throw invalid("code", at.getLocationId() + " is not a storage location");
        }
        if (locks.isLocked(at.getId())) {
            throw ApiException.conflict(at.getLocationId() + " is being counted.", Map.of());
        }
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
