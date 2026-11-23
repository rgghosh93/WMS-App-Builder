package com.wms.control;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wms.common.ActiveStatus;
import com.wms.common.Codes;
import com.wms.common.api.ApiException;
import com.wms.control.ControlViews.ItemRef;
import com.wms.inventory.InventoryRecord;
import com.wms.inventory.InventoryRepository;
import com.wms.inventory.InventoryStatus;
import com.wms.inventory.LocationLocks;
import com.wms.inventory.LpnLifecycle;
import com.wms.inventory.PlannedMoves;
import com.wms.inventory.StockLedger;
import com.wms.inventory.StockLedger.Place;
import com.wms.inventory.StockTransaction.Type;
import com.wms.masterdata.container.Container;
import com.wms.masterdata.container.ContainerRepository;
import com.wms.masterdata.container.ContainerStatus;
import com.wms.masterdata.item.Item;
import com.wms.masterdata.item.ItemFinder;
import com.wms.masterdata.location.Location;
import com.wms.masterdata.location.LocationRepository;
import com.wms.masterdata.location.LocationType;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

/** Correcting how much stock there is. Changes over the limit wait for someone else to approve them. */
@Service
@Transactional
public class AdjustmentService {

    private final AdjustmentRepository adjustments;
    private final InventoryRepository inventory;
    private final ItemFinder items;
    private final LocationRepository locations;
    private final ContainerRepository containers;
    private final StockQueryService stock;
    private final LocationLocks locks;
    private final LpnLifecycle lpns;
    private final StockLedger ledger;
    private final PlannedMoves moves;
    private final ReasonService reasons;

    private final EntityManager em;

    AdjustmentService(AdjustmentRepository adjustments, InventoryRepository inventory, ItemFinder items, LocationRepository locations,
                      ContainerRepository containers, StockQueryService stock, LocationLocks locks, LpnLifecycle lpns,
                      StockLedger ledger, ReasonService reasons, PlannedMoves moves, EntityManager em) {
        this.em = em;
        this.adjustments = adjustments;
        this.inventory = inventory;
        this.items = items;
        this.locations = locations;
        this.containers = containers;
        this.stock = stock;
        this.locks = locks;
        this.lpns = lpns;
        this.ledger = ledger;
        this.reasons = reasons;
        this.moves = moves;
    }

    /**
     * A new quantity for stock there is a record of.
     *
     * @param expectedQuantity what the person saw, so nothing is corrected that has changed since
     */
    public record Change(UUID inventoryId, Integer expectedQuantity, Integer newQuantity, UUID reasonId, String note) {
    }

    /** Stock found where none was recorded: loose at a storage location, or in an LPN that is at one. */
    /** @param owner code of whose item it is, if several have one with this ID */
    public record Add(String itemId, String owner, String locationId, String lpnId, Integer quantity, String lotNumber, LocalDate expiryDate,
                      String serialNumber, InventoryStatus status, UUID reasonId, String note) {
    }

    public record AdjustmentView(UUID id, String number, Adjustment.Status status, ItemRef item, String place, String lotNumber,
                                 String serialNumber, LocalDate expiryDate, InventoryStatus stockStatus, int change, String reason,
                                 String requester, UUID requestedBy, Instant requestedAt, String decider, Instant decidedAt) {
        static AdjustmentView of(Adjustment a) {
            return new AdjustmentView(a.getId(), a.getNumber(), a.getStatus(), ItemRef.of(a.getItem()), a.getPlace(), a.getLotNumber(),
                    a.getSerialNumber(), a.getExpiryDate(), a.getStockStatus(), a.getChange(), a.getReason(), a.getRequester(),
                    a.getRequestedBy(), a.getRequestedAt(), a.getDecider(), a.getDecidedAt());
        }
    }

    @Transactional(readOnly = true)
    public Page<AdjustmentView> search(String q, Adjustment.Status status, Pageable page) {
        String like = q == null || q.isBlank() ? "%" : "%" + q.trim().toLowerCase(Locale.ROOT).replace("\\", "\\\\")
                .replace("%", "\\%").replace("_", "\\_") + "%";
        return adjustments.search(like, status, page).map(AdjustmentView::of);
    }

    public AdjustmentView change(Actor actor, Change request) {
        String why = reasons.text(request.reasonId(), ReasonCode.Kind.ADJUSTMENT, request.note());
        InventoryRecord r = request.inventoryId() == null ? null
                : inventory.findLocked(List.of(request.inventoryId())).stream().findFirst().orElse(null);
        if (r == null) {
            throw ApiException.conflict("That stock is no longer there. Reload and choose again.", Map.of());
        }
        if (request.expectedQuantity() == null || request.expectedQuantity() != r.getQuantity()) {
            throw ApiException.conflict("There are now " + r.getQuantity() + " EA. Check again before correcting it.", Map.of());
        }
        if (request.newQuantity() == null || request.newQuantity() < 0 || request.newQuantity() > 1_000_000) {
            throw invalid("newQuantity", "Enter how many there really are");
        }
        int change = request.newQuantity() - r.getQuantity();
        if (change == 0) {
            throw invalid("newQuantity", "That is how many there are already");
        }
        if (r.getSerialNumber() != null && request.newQuantity() > 1) {
            throw invalid("newQuantity", "A serial number is one each");
        }
        check(r, change);
        if (adjustments.existsByInventoryIdAndStatus(r.getId(), Adjustment.Status.PENDING)) {
            throw ApiException.conflict("Another correction of this stock is waiting for approval.", Map.of());
        }
        Place place = Place.of(r);
        Adjustment a = adjustments.save(new Adjustment(number(), r.getId(), r.getItem(), null,
                r.getContainer() == null ? null : r.getContainer().getId(), r.getLotNumber(), r.getSerialNumber(), r.getExpiryDate(),
                r.getStatus(), words(place), change, why, actor));
        return applyOrWait(a, actor);
    }

    public AdjustmentView add(Actor actor, Add request) {
        String why = reasons.text(request.reasonId(), ReasonCode.Kind.ADJUSTMENT, request.note());
        Item item = items.byId(request.itemId(), request.owner(), "itemId");
        if (item.getStatus() == ActiveStatus.INACTIVE) {
            throw invalid("itemId", item.getItemId() + " is inactive");
        }
        int quantity = request.quantity() == null ? 0 : request.quantity();
        if (quantity < 1 || quantity > 1_000_000) {
            throw invalid("quantity", "Enter how many were found");
        }
        String lot = Codes.normalize(request.lotNumber());
        if (item.isLotControlled() && (lot == null || lot.length() > 40)) {
            throw invalid("lotNumber", "Enter the lot");
        }
        if (item.isExpiryControlled() && request.expiryDate() == null) {
            throw invalid("expiryDate", "Enter the expiry date");
        }
        String serial = Codes.normalize(request.serialNumber());
        if (item.isSerialControlled()) {
            if (serial == null || serial.length() > 60) {
                throw invalid("serialNumber", "Enter the serial number");
            }
            if (quantity != 1) {
                throw invalid("quantity", "A serial number is one each");
            }
            if (inventory.existsByItem_IdAndSerialNumber(item.getId(), serial)) {
                throw invalid("serialNumber", serial + " is already on hand");
            }
        }
        InventoryStatus status = request.status() == null ? InventoryStatus.AVAILABLE : request.status();
        Container lpn = lpn(request.lpnId());
        Location at = lpn == null ? location(request.locationId()) : StockLedger.locationOf(lpn);
        Adjustment a = adjustments.save(new Adjustment(number(), null, item, lpn == null ? at.getId() : null,
                lpn == null ? null : lpn.getId(), item.isLotControlled() ? lot : null, item.isSerialControlled() ? serial : null,
                item.isExpiryControlled() ? request.expiryDate() : null, status, words(Place.at(at, lpn)), quantity, why, actor));
        return applyOrWait(a, actor);
    }

    public AdjustmentView approve(Actor actor, UUID id) {
        Adjustment a = pending(id);
        if (a.getRequestedBy().equals(actor.id())) {
            throw ApiException.conflict("Someone else has to approve what you asked for.", Map.of());
        }
        apply(a, actor);
        a.decide(Adjustment.Status.APPLIED, actor);
        return AdjustmentView.of(a);
    }

    /** Also for taking back one's own request. */
    public AdjustmentView reject(Actor actor, UUID id) {
        Adjustment a = pending(id);
        a.decide(Adjustment.Status.REJECTED, actor);
        return AdjustmentView.of(a);
    }

    private AdjustmentView applyOrWait(Adjustment a, Actor actor) {
        if (Math.abs(a.getChange()) <= reasons.approvalLimit()) {
            apply(a, actor);
            a.applied();
        }
        adjustments.flush();
        return AdjustmentView.of(a);
    }

    /** @param approver who to put in the history beside the person who asked */
    private void apply(Adjustment a, Actor approver) {
        String approved = approver.id().equals(a.getRequestedBy()) ? "" : " (approved by " + approver.name() + ")";
        StockLedger.Note note = StockLedger.Note.by(a.getRequestedBy(), a.getRequester(), a.getNumber()).because(a.getReason() + approved);
        if (a.getInventoryId() != null) {
            InventoryRecord r = inventory.findLocked(List.of(a.getInventoryId())).stream().findFirst()
                    .orElseThrow(() -> ApiException.conflict("That stock is no longer there. Reject this and count it again.", Map.of()));
            check(r, a.getChange());
            changeQuantity(r, a.getChange(), Type.ADJUST, note);
            return;
        }
        if (a.getSerialNumber() != null && inventory.existsByItem_IdAndSerialNumber(a.getItem().getId(), a.getSerialNumber())) {
            throw ApiException.conflict(a.getSerialNumber() + " is on hand by now. Reject this.", Map.of());
        }
        InventoryRecord r = new InventoryRecord(UUID.randomUUID(), a.getItem(), a.getChange());
        r.setLotNumber(a.getLotNumber());
        r.setSerialNumber(a.getSerialNumber());
        r.setExpiryDate(a.getExpiryDate());
        if (a.getStockStatus() != InventoryStatus.AVAILABLE) {
            r.hold(a.getStockStatus(), a.getReason());
        }
        if (a.getContainerId() != null) {
            r.placeIn(lpn(containers.findById(a.getContainerId()).map(Container::getLpnId).orElse("?")));
        } else {
            r.placeIn(location(locations.findById(a.getLocationId()).map(Location::getLocationId).orElse("?")));
        }
        addNew(r, Type.ADJUST, note);
    }

    /** Puts new stock in, adding to what is the same. */
    void addNew(InventoryRecord r, Type type, StockLedger.Note note) {
        List<InventoryRecord> there = r.getContainer() == null ? inventory.findByLocation_IdIn(List.of(r.getLocation().getId()))
                : inventory.findByContainer_IdIn(List.of(r.getContainer().getId()));
        InventoryRecord same = there.stream().filter(r::mergesWith).findFirst().orElse(null);
        if (same != null) {
            // Read again under a lock: someone else may be adding to it at the same time.
            em.refresh(same, LockModeType.PESSIMISTIC_WRITE);
            same.setQuantity(same.getQuantity() + r.getQuantity());
        } else {
            inventory.save(r);
        }
        ledger.log(type, r, r.getQuantity(), Place.OUTSIDE, Place.of(r), note);
    }

    /** Changes a quantity; what has nothing left is gone, and so is the LPN it was in if that is empty. */
    void changeQuantity(InventoryRecord r, int change, Type type, StockLedger.Note note) {
        Place place = Place.of(r);
        ledger.log(type, r, Math.abs(change), change > 0 ? Place.OUTSIDE : place, change > 0 ? place : Place.OUTSIDE, note);
        if (r.getQuantity() + change > 0) {
            r.setQuantity(r.getQuantity() + change);
            return;
        }
        Container in = r.getContainer();
        inventory.delete(r);
        if (in != null) {
            lpns.retireIfEmptied(in);
        }
    }

    /** Why stock can't be corrected, or not by this much. */
    private void check(InventoryRecord r, int change) {
        Location at = StockLedger.locationOf(r);
        if (at == null) {
            throw ApiException.conflict("Someone is working with that stock. Correct it when it has been put down.", Map.of());
        }
        if (locks.isLocked(at.getId())) {
            throw ApiException.conflict(at.getLocationId() + " is being counted.", Map.of());
        }
        int reserved = stock.reserved(List.of(r)).getOrDefault(r.getId(), 0);
        if (reserved > 0 && at.getType() != LocationType.STORAGE) {
            throw ApiException.conflict("That stock is picked for an order and can't be corrected here.", Map.of());
        }
        if (r.getQuantity() + change < reserved) {
            // Moves nobody has started can be planned again; orders come first.
            moves.callOff(List.of(r.getId()), "The stock was corrected");
            reserved = stock.reserved(List.of(r)).getOrDefault(r.getId(), 0);
        }
        if (r.getQuantity() + change < reserved) {
            throw ApiException.conflict(reserved + " EA are reserved, so at least that many have to stay. "
                    + "Put the stock on hold or count the location instead.", Map.of("newQuantity", "at least " + reserved));
        }
    }

    private Container lpn(String scanned) {
        String code = Codes.normalize(scanned);
        if (code == null) {
            return null;
        }
        Container lpn = containers.findByLpnId(code).orElseThrow(() -> invalid("lpnId", code + " is not a known LPN"));
        Location at = StockLedger.locationOf(lpn);
        if (at == null || at.getType() != LocationType.STORAGE || lpn.getStatus() == ContainerStatus.RETIRED
                || lpn.getStatus() == ContainerStatus.ON_HOLD) {
            throw invalid("lpnId", code + " is not in storage");
        }
        requireUnlocked(at);
        return lpn;
    }

    private Location location(String scanned) {
        String code = Codes.normalize(scanned);
        Location at = code == null ? null : locations.findByLocationId(code).orElse(null);
        if (at == null || at.getType() != LocationType.STORAGE) {
            throw invalid("locationId", code == null ? "Choose a location or an LPN" : code + " is not a storage location");
        }
        requireUnlocked(at);
        return at;
    }

    private void requireUnlocked(Location at) {
        if (locks.isLocked(at.getId())) {
            throw ApiException.conflict(at.getLocationId() + " is being counted.", Map.of());
        }
    }

    private Adjustment pending(UUID id) {
        Adjustment a = adjustments.findLocked(id).orElseThrow(() -> ApiException.notFound("Adjustment not found"));
        if (a.getStatus() != Adjustment.Status.PENDING) {
            throw ApiException.conflict(a.getNumber() + " has already been decided.", Map.of());
        }
        return a;
    }

    private String number() {
        return "AD%06d".formatted(adjustments.nextNumber());
    }

    private static String words(Place place) {
        return place.lpn() == null ? place.location() : place.lpn() + " at " + place.location();
    }

    private static ApiException invalid(String field, String message) {
        return ApiException.invalid(message, Map.of(field, message));
    }
}
