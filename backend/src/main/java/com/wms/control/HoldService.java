package com.wms.control;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wms.common.Codes;
import com.wms.common.api.ApiException;
import com.wms.control.ControlViews.HoldResult;
import com.wms.inventory.InventoryRecord;
import com.wms.inventory.InventoryRepository;
import com.wms.inventory.InventoryStatus;
import com.wms.inventory.PlannedMoves;
import com.wms.inventory.StockLedger;
import com.wms.inventory.StockLedger.Place;
import com.wms.inventory.StockReservations;
import com.wms.inventory.StockReservations.Released;
import com.wms.inventory.StockTransaction.Type;
import com.wms.masterdata.container.Container;
import com.wms.masterdata.container.ContainerRepository;
import com.wms.masterdata.item.Item;
import com.wms.masterdata.item.ItemRepository;
import com.wms.masterdata.location.Location;
import com.wms.masterdata.location.LocationRepository;
import com.wms.putaway.StorageSpace;

/**
 * Taking stock out of use and back. Orders nobody has started picking lose what is held and get
 * other stock if there is any; what is already being picked or is picked stays with its order, but
 * goes no further until it is released.
 */
@Service
@Transactional
public class HoldService {

    private final InventoryRepository inventory;
    private final ContainerRepository containers;
    private final LocationRepository locations;
    private final ItemRepository items;
    private final StockQueryService stock;
    private final StockReservations reservations;
    private final PlannedMoves moves;
    private final StorageSpace space;
    private final StockLedger ledger;
    private final ReasonService reasons;

    HoldService(InventoryRepository inventory, ContainerRepository containers, LocationRepository locations, ItemRepository items,
                StockQueryService stock, StockReservations reservations, PlannedMoves moves, StorageSpace space, StockLedger ledger, ReasonService reasons) {
        this.inventory = inventory;
        this.containers = containers;
        this.locations = locations;
        this.items = items;
        this.stock = stock;
        this.reservations = reservations;
        this.moves = moves;
        this.space = space;
        this.ledger = ledger;
        this.reasons = reasons;
    }

    /** What to hold: one of an LPN, a location, an item, or a lot of an item. */
    public record Scope(String lpnId, String locationId, String itemId, String lotNumber) {
    }

    /** @param damaged damaged rather than on hold */
    public record Hold(Scope scope, Boolean damaged, UUID reasonId, String note) {
    }

    public record Release(List<UUID> inventoryIds, UUID reasonId, String note) {
    }

    /** What a hold would do. */
    @Transactional(readOnly = true)
    public HoldResult preview(Scope scope) {
        List<InventoryRecord> found = inScope(scope).stream().filter(r -> r.getStatus() == InventoryStatus.AVAILABLE).toList();
        return new HoldResult(found.size(), eaches(found), 0, stock.reserved(found).values().stream().mapToInt(Integer::intValue).sum());
    }

    public HoldResult hold(Actor actor, Hold request) {
        InventoryStatus as = Boolean.TRUE.equals(request.damaged()) ? InventoryStatus.DAMAGED : InventoryStatus.ON_HOLD;
        String why = reasons.text(request.reasonId(), ReasonCode.Kind.HOLD, request.note());
        List<InventoryRecord> found = lock(inScope(request.scope())).stream().filter(r -> r.getStatus() == InventoryStatus.AVAILABLE
                || as == InventoryStatus.DAMAGED && r.getStatus() == InventoryStatus.ON_HOLD).toList();
        if (found.isEmpty()) {
            throw ApiException.conflict("There is nothing there to hold.", Map.of());
        }
        Map<UUID, Integer> keepNone = new HashMap<>();
        found.forEach(r -> keepNone.put(r.getId(), 0));
        moves.callOff(keepNone.keySet(), "Stock put on hold (" + why + ")");
        Released released = reservations.release(keepNone, "stock put on hold (" + why + ")");
        for (InventoryRecord r : found) {
            InventoryStatus before = r.getStatus();
            r.hold(as, why);
            ledger.log(Type.HOLD, r, r.getQuantity(), Place.of(r), Place.of(r), before, as, actor.note(null).because(why));
        }
        inventory.flush();
        reservations.reallocate(released);
        int stuck = stock.reserved(found).values().stream().mapToInt(Integer::intValue).sum();
        return new HoldResult(found.size(), eaches(found), released.orderIds().size(), stuck);
    }

    public HoldResult release(Actor actor, Release request) {
        String why = reasons.text(request.reasonId(), ReasonCode.Kind.HOLD, request.note());
        List<UUID> ids = request.inventoryIds() == null ? List.of() : request.inventoryIds().stream().distinct().toList();
        List<InventoryRecord> found = ids.isEmpty() ? List.of() : inventory.findLocked(ids);
        if (found.isEmpty()) {
            throw ApiException.invalid("Choose what to release", Map.of("inventoryIds", "choose what to release"));
        }
        if (found.size() != ids.size() || found.stream().anyMatch(r -> r.getStatus() == InventoryStatus.AVAILABLE)) {
            throw ApiException.conflict("Some of it has changed. Reload and choose again.", Map.of());
        }
        for (InventoryRecord r : found) {
            InventoryStatus before = r.getStatus();
            r.release();
            ledger.log(Type.RELEASE, r, r.getQuantity(), Place.of(r), Place.of(r), before, InventoryStatus.AVAILABLE,
                    actor.note(null).because(why));
        }
        return new HoldResult(found.size(), eaches(found), 0, 0);
    }

    private List<InventoryRecord> lock(List<InventoryRecord> records) {
        return records.isEmpty() ? records : inventory.findLocked(records.stream().map(InventoryRecord::getId).toList());
    }

    private List<InventoryRecord> inScope(Scope scope) {
        String lpn = scope == null ? null : Codes.normalize(scope.lpnId());
        String location = scope == null ? null : Codes.normalize(scope.locationId());
        String itemCode = scope == null ? null : Codes.normalize(scope.itemId());
        String lot = scope == null ? null : Codes.normalize(scope.lotNumber());
        if (lpn != null) {
            Container container = containers.findByLpnId(lpn).orElseThrow(() -> invalid("lpnId", lpn + " is not a known LPN"));
            return space.contents(container);
        }
        if (location != null) {
            Location at = locations.findByLocationId(location).orElseThrow(() -> invalid("locationId", location + " is not a known location"));
            List<InventoryRecord> all = new ArrayList<>(inventory.findByLocation_IdIn(List.of(at.getId())));
            containers.findByCurrentLocation_IdIn(List.of(at.getId())).forEach(c -> all.addAll(space.contents(c)));
            return all;
        }
        if (itemCode == null) {
            throw invalid("scope", "Choose an LPN, a location, an item or a lot");
        }
        Item item = items.findByItemId(itemCode).orElseThrow(() -> invalid("itemId", itemCode + " is not a known item"));
        return lot == null ? inventory.findByItem_Id(item.getId()) : inventory.findByItem_IdAndLotNumber(item.getId(), lot);
    }

    private static int eaches(Collection<InventoryRecord> records) {
        return records.stream().mapToInt(InventoryRecord::getQuantity).sum();
    }

    private static ApiException invalid(String field, String message) {
        return ApiException.invalid(message, Map.of(field, message));
    }
}
