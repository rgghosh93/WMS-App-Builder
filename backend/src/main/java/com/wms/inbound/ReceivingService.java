package com.wms.inbound;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wms.common.ActiveStatus;
import com.wms.common.Codes;
import com.wms.common.api.ApiException;
import com.wms.inbound.AsnEvent.Type;
import com.wms.inbound.InboundViews.AsnDetail;
import com.wms.inbound.InboundViews.ContainerTypeOption;
import com.wms.inbound.InboundViews.LineView;
import com.wms.inbound.InboundViews.LocationRef;
import com.wms.inbound.InboundViews.LpnCheck;
import com.wms.inbound.InboundViews.LpnKind;
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
import com.wms.masterdata.container.ContainerStatus;
import com.wms.masterdata.container.ContainerType;
import com.wms.masterdata.container.ContainerTypeRepository;
import com.wms.masterdata.container.NewLpnCheck;
import com.wms.masterdata.item.Item;
import com.wms.masterdata.item.ItemScan;
import com.wms.masterdata.location.Location;
import com.wms.masterdata.location.LocationRepository;
import com.wms.masterdata.location.LocationType;
import com.wms.users.AppUser;
import com.wms.users.AppUserRepository;

import jakarta.persistence.EntityManager;

/**
 * Receiving an ASN at a receiving location or dock door, into LPNs: whole LPNs the vendor announced, or item by
 * item. Stock is on hand from here, but isn't allocated until it is put away in storage.
 */
@Service
@Transactional
public class ReceivingService {

    private static final int MAX_EACHES = 1_000_000;
    private static final int MAX_LOT = 40;
    private static final int MAX_SERIAL = 60;

    private final AsnRepository asns;
    private final AsnReceiptRepository receipts;
    private final AsnEventRepository events;
    private final AsnService asnService;
    private final InventoryRepository inventory;
    private final ContainerRepository containers;
    private final ContainerTypeRepository types;
    private final LocationRepository locations;
    private final NewLpnCheck newLpns;
    private final AppUserRepository users;
    private final EntityManager em;
    private final List<ReceivedLpns> listeners;
    private final StockLedger ledger;

    ReceivingService(AsnRepository asns, AsnReceiptRepository receipts, AsnEventRepository events, AsnService asnService,
                     InventoryRepository inventory, ContainerRepository containers, ContainerTypeRepository types,
                     LocationRepository locations, NewLpnCheck newLpns, AppUserRepository users, EntityManager em,
                     List<ReceivedLpns> listeners, StockLedger ledger) {
        this.asns = asns;
        this.receipts = receipts;
        this.events = events;
        this.asnService = asnService;
        this.inventory = inventory;
        this.containers = containers;
        this.types = types;
        this.locations = locations;
        this.newLpns = newLpns;
        this.users = users;
        this.em = em;
        this.listeners = listeners;
        this.ledger = ledger;
    }

    /**
     * Items received into an LPN.
     *
     * @param requestId       made by the device for this receipt; sending it again does nothing
     * @param containerTypeId what the LPN is, when it is new
     * @param itemBarcode     the scanned barcode or item ID; it decides the unit {@code quantity} is in
     * @param serialNumbers   one for each each, for serial-tracked items
     * @param condition       AVAILABLE when left out
     * @param allowOver       receive more than the ASN says
     */
    public record Receive(UUID requestId, String locationId, String lpnId, UUID containerTypeId, String itemBarcode,
                          Integer quantity, String lotNumber, LocalDate expiryDate, List<String> serialNumbers,
                          InventoryStatus condition, Boolean allowOver) {
    }

    /** Everything the vendor said is in an LPN. */
    public record ReceiveLpn(UUID requestId, String locationId, String lpnId, UUID containerTypeId, InventoryStatus condition) {
    }

    // ---------- Looking ----------

    @Transactional(readOnly = true)
    public List<LocationRef> locations() {
        return Arrays.stream(LocationType.values()).filter(LocationType::isForReceiving)
                .flatMap(t -> locations.findByTypeOrderByLocationId(t).stream()).map(LocationRef::of).toList();
    }

    @Transactional(readOnly = true)
    public LocationRef location(String scanned) {
        return LocationRef.of(loadLocation(scanned));
    }

    /** What a scanned LPN is. Blank makes a new LPN number. */
    public LpnCheck checkLpn(UUID asnId, String locationId, String scanned) {
        Asn asn = asns.findById(asnId).orElseThrow(() -> ApiException.notFound("ASN not found"));
        requireOpen(asn);
        Location location = loadLocation(locationId);
        String lpnId = Codes.normalize(scanned);
        if (lpnId == null) {
            do {
                lpnId = "LP%08d".formatted(asns.nextLpn());
            } while (containers.findByLpnId(lpnId).isPresent());
        }
        Container container = usable(asn, location, lpnId);
        List<AsnLine> expected = announced(asn, lpnId);
        LpnKind kind = !expected.isEmpty() ? LpnKind.ANNOUNCED : container == null ? LpnKind.NEW : LpnKind.OPEN;
        return new LpnCheck(lpnId, kind, container == null,
                container == null ? null : container.getContainerType().getDescription(),
                expected.stream().map(LineView::of).toList(), whyNotAll(expected),
                container == null ? lpnTypes().stream().map(ContainerTypeOption::of).toList() : List.of());
    }

    // ---------- Receiving ----------

    public AsnDetail receive(UUID userId, UUID asnId, Receive request) {
        Asn asn = lock(asnId);
        if (seen(request.requestId())) {
            return asnService.detail(asn);
        }
        requireOpen(asn);
        Location location = loadLocation(request.locationId());
        String lpnId = requireLpn(request.lpnId());

        String scanned = Codes.normalize(request.itemBarcode());
        List<AsnLine> lines = scanned == null ? List.of() : asn.getLines().stream()
                .filter(l -> ItemScan.of(l.getItem()).stream().anyMatch(s -> s.value().equalsIgnoreCase(scanned))).toList();
        if (lines.isEmpty()) {
            throw invalid("itemBarcode", scanned == null ? "Scan an item" : scanned + " is not on " + asn.getAsnNumber());
        }
        Item item = lines.getFirst().getItem();
        ItemScan scan = ItemScan.of(item).stream().filter(s -> s.value().equalsIgnoreCase(scanned)).findFirst().orElseThrow();
        if (request.quantity() == null || request.quantity() < 1) {
            throw invalid("quantity", "Enter the quantity received");
        }
        long eaches = (long) request.quantity() * scan.eachesPerUnit();
        if (eaches > MAX_EACHES) {
            throw invalid("quantity", "At most " + MAX_EACHES + " EA at a time");
        }
        InventoryStatus condition = Objects.requireNonNullElse(request.condition(), InventoryStatus.AVAILABLE);
        String lot = checkedLot(item, request.lotNumber());
        LocalDate expiry = checkedExpiry(item, request.expiryDate(), condition);
        List<String> serials = checkedSerials(item, request.serialNumbers(), (int) eaches);

        List<AsnLine> bestFirst = lines.stream().sorted(Comparator
                .comparingInt((AsnLine l) -> lpnId.equals(l.getLpnId()) ? 0 : l.getLpnId() == null ? 1 : 2)
                .thenComparingInt(l -> Objects.equals(lot, l.getLotNumber()) ? 0 : l.getLotNumber() == null ? 1 : 2)
                .thenComparingInt(AsnLine::getLineNumber)).toList();
        int expected = bestFirst.stream().mapToInt(AsnLine::remaining).sum();
        int over = (int) eaches - expected;
        if (over > 0 && !Boolean.TRUE.equals(request.allowOver())) {
            throw ApiException.conflict("That is %d EA more of %s than %s says (%d EA left).".formatted(over, item.getItemId(),
                    asn.getAsnNumber(), expected), Map.of("over", String.valueOf(over)));
        }

        Container container = open(asn, location, lpnId, request.containerTypeId());
        AppUser user = users.getReferenceById(userId);
        List<AsnReceipt> made = new ArrayList<>();
        int left = (int) eaches;
        int serial = 0;
        for (AsnLine line : bestFirst) {
            // What doesn't fit on any line goes on the best one, once the lines are full.
            int take = Math.min(left, line.remaining());
            left -= take;
            serial = record(made, line, take, request.requestId(), container, location, lot, serials, serial, expiry, condition, user);
        }
        if (left > 0) {
            record(made, bestFirst.getFirst(), left, request.requestId(), container, location, lot, serials, serial, expiry,
                    condition, user);
        }
        stock(container, item, (int) eaches, lot, serials, expiry, condition, Note.by(userId, asn.getAsnNumber()));
        listeners.forEach(l -> l.received(container, location));
        finish(asn, "%s received %d EA of %s%s into %s at %s%s%s".formatted(user.getDisplayName(), eaches, item.getItemId(),
                lot == null ? "" : " lot " + lot, lpnId, location.getLocationId(), note(condition),
                over > 0 ? "; " + over + " EA over" : ""));
        return asnService.detail(asn);
    }

    /** Receives a whole LPN the vendor announced, as announced. */
    public AsnDetail receiveLpn(UUID userId, UUID asnId, ReceiveLpn request) {
        Asn asn = lock(asnId);
        if (seen(request.requestId())) {
            return asnService.detail(asn);
        }
        requireOpen(asn);
        Location location = loadLocation(request.locationId());
        String lpnId = requireLpn(request.lpnId());
        List<AsnLine> lines = announced(asn, lpnId);
        if (lines.isEmpty()) {
            throw invalid("lpnId", lpnId + " has nothing left to receive on " + asn.getAsnNumber());
        }
        String whyNot = whyNotAll(lines);
        if (whyNot != null) {
            throw invalid("lpnId", whyNot);
        }
        InventoryStatus condition = Objects.requireNonNullElse(request.condition(), InventoryStatus.AVAILABLE);
        for (AsnLine line : lines) {
            checkedExpiry(line.getItem(), line.getExpiryDate(), condition);
        }
        Container container = open(asn, location, lpnId, request.containerTypeId());
        AppUser user = users.getReferenceById(userId);
        List<AsnReceipt> made = new ArrayList<>();
        int eaches = 0;
        for (AsnLine line : lines) {
            int quantity = line.remaining();
            record(made, line, quantity, request.requestId(), container, location, line.getLotNumber(), List.of(), 0,
                    line.getExpiryDate(), condition, user);
            stock(container, line.getItem(), quantity, line.getLotNumber(), List.of(), line.getExpiryDate(), condition,
                    Note.by(userId, asn.getAsnNumber()));
            eaches += quantity;
        }
        listeners.forEach(l -> l.received(container, location));
        finish(asn, "%s received %s at %s: %d EA as announced%s".formatted(user.getDisplayName(), lpnId,
                location.getLocationId(), eaches, note(condition)));
        return asnService.detail(asn);
    }

    /** Takes back a receipt made by mistake. Only while the stock is still where it was received. */
    public AsnDetail reverse(UUID userId, UUID receiptId) {
        AsnReceipt receipt = receipts.findById(receiptId).orElseThrow(() -> ApiException.notFound("Receipt not found"));
        Asn asn = lock(receipt.getAsn().getId());
        em.refresh(receipt); // someone else may have reversed it while we waited for the ASN
        requireOpen(asn);
        if (receipt.isReversed()) {
            throw ApiException.conflict("That receipt is already reversed.", Map.of());
        }
        Container container = containers.findLockedByLpnId(receipt.getContainer().getLpnId()).orElseThrow();
        Location at = container.getCurrentLocation();
        InventoryRecord record = inventory.findByContainer_IdIn(List.of(container.getId())).stream()
                .filter(r -> same(r, receipt.getLine().getItem(), receipt.getLotNumber(), receipt.getSerialNumber(),
                        receipt.getExpiryDate(), receipt.getInventoryStatus()) && r.getQuantity() >= receipt.getQuantity())
                .findFirst().orElse(null);
        if (record == null || at == null || !at.getType().isForReceiving() || container.getParent() != null) {
            throw ApiException.conflict("It can't be reversed: the stock has moved or changed since.", Map.of());
        }
        ledger.log(StockTransaction.Type.RECEIVE_UNDO, record, receipt.getQuantity(), Place.of(record), Place.OUTSIDE,
                Note.by(userId, asn.getAsnNumber()));
        if (record.getQuantity() == receipt.getQuantity()) {
            inventory.delete(record);
        } else {
            record.setQuantity(record.getQuantity() - receipt.getQuantity());
        }
        AppUser user = users.getReferenceById(userId);
        receipt.getLine().reverse(receipt.getQuantity());
        receipt.reverse(user);
        receipts.flush();
        if (inventory.countByContainer_Id(container.getId()) == 0) {
            listeners.forEach(l -> l.emptied(container));
            if (container.isPermanent()) {
                container.setStatus(ContainerStatus.AVAILABLE);
            }
        }
        events.save(new AsnEvent(asn, Type.REVERSED, "%s reversed the receipt of %d EA of %s into %s".formatted(
                user.getDisplayName(), receipt.getQuantity(), receipt.getLine().getItem().getItemId(), container.getLpnId())));
        asns.flush();
        return asnService.detail(asn);
    }

    /**
     * Nothing more is coming.
     *
     * @param acceptShort close it even though some lines came short
     */
    public AsnDetail close(UUID userId, UUID asnId, boolean acceptShort) {
        Asn asn = lock(asnId);
        requireOpen(asn);
        if (!asn.anyReceived()) {
            throw ApiException.conflict("Nothing has been received on " + asn.getAsnNumber() + ". Cancel it instead.", Map.of());
        }
        int shortBy = asn.getLines().stream().mapToInt(AsnLine::remaining).sum();
        int over = asn.getLines().stream().mapToInt(l -> Math.max(0, l.getReceivedQuantity() - l.getExpectedQuantity())).sum();
        if (shortBy > 0 && !acceptShort) {
            throw ApiException.conflict(shortBy + " EA of " + asn.getAsnNumber() + " have not been received.",
                    Map.of("short", String.valueOf(shortBy)));
        }
        AppUser user = users.getReferenceById(userId);
        asn.close(user);
        // LPNs emptied by reversed receipts are of no more use.
        Set<Container> used = new LinkedHashSet<>();
        receipts.findByAsn_IdOrderByReceivedAtDescRequestPartAsc(asnId).forEach(r -> used.add(r.getContainer()));
        for (Container c : used) {
            if (!c.isPermanent() && c.getStatus() == ContainerStatus.IN_USE && inventory.countByContainer_Id(c.getId()) == 0
                    && containers.countByParent_Id(c.getId()) == 0) {
                c.setStatus(ContainerStatus.RETIRED);
                c.setCurrentLocation(null);
            }
        }
        long received = asn.getLines().stream().mapToLong(AsnLine::getReceivedQuantity).sum();
        long expected = asn.getLines().stream().mapToLong(AsnLine::getExpectedQuantity).sum();
        events.save(new AsnEvent(asn, Type.CLOSED, "%s closed it: %d of %d EA received%s%s".formatted(user.getDisplayName(),
                received, expected, shortBy > 0 ? ", " + shortBy + " short" : "", over > 0 ? ", " + over + " over" : "")));
        asns.flush();
        return asnService.detail(asn);
    }

    // ---------- Stock and LPNs ----------

    /** Writes the receipts for part of a line; serial-tracked items get one each. Returns the next serial to use. */
    private int record(List<AsnReceipt> made, AsnLine line, int quantity, UUID requestId, Container container, Location location,
                       String lot, List<String> serials, int nextSerial, LocalDate expiry, InventoryStatus condition, AppUser user) {
        if (quantity <= 0) {
            return nextSerial;
        }
        line.receive(quantity);
        if (serials.isEmpty()) {
            made.add(receipts.save(new AsnReceipt(line, requestId, made.size(), container, location, quantity, lot, null,
                    expiry, condition, user)));
            return nextSerial;
        }
        for (int i = 0; i < quantity; i++) {
            made.add(receipts.save(new AsnReceipt(line, requestId, made.size(), container, location, 1, lot,
                    serials.get(nextSerial + i), expiry, condition, user)));
        }
        return nextSerial + quantity;
    }

    /** Puts received stock in the LPN, adding to what is the same. */
    private void stock(Container container, Item item, int quantity, String lot, List<String> serials, LocalDate expiry,
                       InventoryStatus condition, Note note) {
        if (serials.isEmpty()) {
            InventoryRecord match = inventory.findByContainer_IdIn(List.of(container.getId())).stream()
                    .filter(r -> same(r, item, lot, null, expiry, condition)).findFirst().orElse(null);
            if (match != null) {
                match.setQuantity(match.getQuantity() + quantity);
                ledger.log(StockTransaction.Type.RECEIVE, match, quantity, Place.OUTSIDE, Place.of(match), note);
                return;
            }
        }
        for (int i = 0; i < Math.max(1, serials.size()); i++) {
            InventoryRecord record = new InventoryRecord(UUID.randomUUID(), item, serials.isEmpty() ? quantity : 1);
            record.setLotNumber(lot);
            record.setExpiryDate(expiry);
            record.setSerialNumber(serials.isEmpty() ? null : serials.get(i));
            if (condition != InventoryStatus.AVAILABLE) {
                record.hold(condition, condition == InventoryStatus.DAMAGED ? "Received damaged" : "Received on hold");
            }
            record.placeIn(container);
            inventory.save(record);
            ledger.log(StockTransaction.Type.RECEIVE, record, record.getQuantity(), Place.OUTSIDE, Place.of(record), note);
        }
    }

    private static boolean same(InventoryRecord r, Item item, String lot, String serial, LocalDate expiry, InventoryStatus status) {
        return r.getItem().getId().equals(item.getId()) && Objects.equals(r.getLotNumber(), lot)
                && Objects.equals(r.getSerialNumber(), serial) && Objects.equals(r.getExpiryDate(), expiry) && r.getStatus() == status;
    }

    /** The container for an LPN if it exists and can be received into; null if it is new. Throws if it can't be used. */
    private Container usable(Asn asn, Location location, String lpnId) {
        Container container = containers.findByLpnId(lpnId).orElse(null);
        if (container == null) {
            newLpns.problemWith(lpnId).ifPresent(problem -> {
                throw invalid("lpnId", problem);
            });
            return null;
        }
        if (container.getContainerType().getCategory() == ContainerCategory.BIN) {
            throw invalid("lpnId", lpnId + " is a storage bin");
        }
        if (container.getParent() != null) {
            throw invalid("lpnId", lpnId + " is inside " + container.getParent().getLpnId());
        }
        boolean ours = container.getStatus() == ContainerStatus.IN_USE
                && receipts.existsByAsn_IdAndContainer_Id(asn.getId(), container.getId());
        if (ours) {
            Location at = container.getCurrentLocation();
            if (at == null || !at.getId().equals(location.getId())) {
                throw invalid("lpnId", lpnId + " is " + (at == null ? "not here" : "at " + at.getLocationId()));
            }
            return container;
        }
        if (container.getStatus() != ContainerStatus.AVAILABLE) {
            throw invalid("lpnId", lpnId + (container.getStatus() == ContainerStatus.IN_USE ? " is already in use"
                    : container.getStatus() == ContainerStatus.ON_HOLD ? " is on hold" : " is retired"));
        }
        if (inventory.countByContainer_Id(container.getId()) > 0 || containers.countByParent_Id(container.getId()) > 0) {
            throw invalid("lpnId", lpnId + " is not empty");
        }
        return container;
    }

    /** Takes the LPN into use where it is received, creating it if it is new. */
    private Container open(Asn asn, Location location, String lpnId, UUID typeId) {
        containers.findLockedByLpnId(lpnId);
        Container container = usable(asn, location, lpnId);
        if (container == null) {
            ContainerType type = lpnTypes().stream().filter(t -> t.getId().equals(typeId)).findFirst()
                    .orElseThrow(() -> invalid("containerTypeId", "Choose what " + lpnId + " is"));
            container = new Container(UUID.randomUUID());
            container.setLpnId(lpnId);
            container.setContainerType(type);
            container.setPermanent(false);
        }
        container.setStatus(ContainerStatus.IN_USE);
        container.setCurrentLocation(location);
        return containers.saveAndFlush(container);
    }

    private List<ContainerType> lpnTypes() {
        return types.findAll().stream()
                .filter(t -> t.getStatus() == ActiveStatus.ACTIVE && t.getCategory() != ContainerCategory.BIN)
                .sorted(Comparator.comparing(ContainerType::getContainerTypeId)).toList();
    }

    private static List<AsnLine> announced(Asn asn, String lpnId) {
        return asn.getLines().stream().filter(l -> lpnId.equals(l.getLpnId()) && l.remaining() > 0).toList();
    }

    /** Why these lines can't be received as announced: something has to be captured that the vendor didn't say. */
    private static String whyNotAll(List<AsnLine> lines) {
        for (AsnLine line : lines) {
            Item item = line.getItem();
            if (item.isSerialControlled()) {
                return item.getItemId() + " needs its serial numbers scanned";
            }
            if (item.isLotControlled() && line.getLotNumber() == null) {
                return item.getItemId() + " needs its lot";
            }
            if (item.isExpiryControlled() && line.getExpiryDate() == null) {
                return item.getItemId() + " needs its expiry date";
            }
        }
        return null;
    }

    // ---------- Checks ----------

    private static String checkedLot(Item item, String lotNumber) {
        if (!item.isLotControlled()) {
            return null;
        }
        String lot = Codes.normalize(lotNumber);
        if (lot == null || lot.length() > MAX_LOT) {
            throw invalid("lotNumber", lot == null ? "Enter the lot of " + item.getItemId() : "At most " + MAX_LOT + " characters");
        }
        return lot;
    }

    private static LocalDate checkedExpiry(Item item, LocalDate expiry, InventoryStatus condition) {
        if (!item.isExpiryControlled()) {
            return null;
        }
        if (expiry == null) {
            throw invalid("expiryDate", "Enter the expiry date of " + item.getItemId());
        }
        if (expiry.isBefore(LocalDate.now()) && condition == InventoryStatus.AVAILABLE) {
            throw invalid("expiryDate", item.getItemId() + " has already expired. Receive it on hold or damaged");
        }
        return expiry;
    }

    private List<String> checkedSerials(Item item, List<String> serialNumbers, int eaches) {
        if (!item.isSerialControlled()) {
            return List.of();
        }
        List<String> serials = serialNumbers == null ? List.of() : serialNumbers.stream().map(Codes::normalize).filter(Objects::nonNull).toList();
        if (serials.size() != eaches) {
            throw invalid("serialNumbers", "Scan " + eaches + " serial number" + (eaches == 1 ? "" : "s") + " of " + item.getItemId());
        }
        Set<String> unique = new HashSet<>();
        for (String serial : serials) {
            if (serial.length() > MAX_SERIAL) {
                throw invalid("serialNumbers", "At most " + MAX_SERIAL + " characters");
            }
            if (!unique.add(serial) || inventory.existsByItem_IdAndSerialNumber(item.getId(), serial)) {
                throw invalid("serialNumbers", serial + " has already been received");
            }
        }
        return serials;
    }

    private void finish(Asn asn, String message) {
        asn.startReceiving();
        events.save(new AsnEvent(asn, Type.RECEIVED, message));
        asns.flush();
    }

    private static String note(InventoryStatus condition) {
        return switch (condition) {
            case AVAILABLE -> "";
            case ON_HOLD -> ", on hold";
            case DAMAGED -> ", damaged";
        };
    }

    /** Whether this request was already done, so it isn't done twice. */
    private boolean seen(UUID requestId) {
        if (requestId == null) {
            throw invalid("requestId", "A request ID is required");
        }
        return receipts.existsByRequestId(requestId);
    }

    private Asn lock(UUID id) {
        return asns.findLocked(id).orElseThrow(() -> ApiException.notFound("ASN not found"));
    }

    private static void requireOpen(Asn asn) {
        if (!asn.isOpen()) {
            throw ApiException.conflict(asn.getAsnNumber() + (asn.getStatus() == AsnStatus.CANCELLED
                    ? " is cancelled." : " is already closed."), Map.of());
        }
    }

    private static String requireLpn(String scanned) {
        String lpnId = Codes.normalize(scanned);
        if (lpnId == null) {
            throw invalid("lpnId", "Scan an LPN");
        }
        return lpnId;
    }

    private Location loadLocation(String scanned) {
        String code = Codes.normalize(scanned);
        Location location = code == null ? null : locations.findByLocationId(code).orElse(null);
        if (location == null || !location.getType().isForReceiving()) {
            throw invalid("locationId", code == null ? "Scan a receiving location or dock door"
                    : code + " is not a receiving location or dock door");
        }
        return location;
    }

    private static ApiException invalid(String field, String message) {
        return ApiException.invalid(message, Map.of(field, message));
    }
}
