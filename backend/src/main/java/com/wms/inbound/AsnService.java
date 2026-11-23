package com.wms.inbound;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wms.common.ActiveStatus;
import com.wms.common.Codes;
import com.wms.common.api.ApiException;
import com.wms.inbound.AsnEvent.Type;
import com.wms.inbound.InboundViews.AsnDetail;
import com.wms.inbound.InboundViews.AsnSummary;
import com.wms.inbound.InboundViews.EventView;
import com.wms.inbound.InboundViews.LineView;
import com.wms.inbound.InboundViews.ReceiptView;
import com.wms.masterdata.container.ContainerRepository;
import com.wms.masterdata.container.NewLpnCheck;
import com.wms.masterdata.item.Item;
import com.wms.masterdata.item.ItemRepository;
import com.wms.masterdata.item.ItemUsage;
import com.wms.masterdata.owner.Owner;
import com.wms.masterdata.owner.OwnerRef;
import com.wms.masterdata.owner.Owners;

/** Takes in ASNs and shows how they are going. */
@Service
@Transactional
public class AsnService implements ItemUsage {

    private final AsnRepository asns;
    private final AsnReceiptRepository receipts;
    private final AsnEventRepository events;
    private final ItemRepository items;
    private final ContainerRepository containers;
    private final NewLpnCheck newLpns;
    private final Owners owners;

    AsnService(AsnRepository asns, AsnReceiptRepository receipts, AsnEventRepository events, ItemRepository items,
               ContainerRepository containers, NewLpnCheck newLpns, Owners owners) {
        this.asns = asns;
        this.receipts = receipts;
        this.events = events;
        this.items = items;
        this.containers = containers;
        this.newLpns = newLpns;
        this.owners = owners;
    }

    @Transactional(readOnly = true)
    public Page<AsnSummary> search(AsnSearch search, Pageable pageable) {
        return asns.findAll(search.toSpecification(), pageable).map(AsnSummary::of);
    }

    /** ASNs that can be received, soonest first. */
    @Transactional(readOnly = true)
    public List<AsnSummary> open() {
        return asns.findOpen().stream().map(AsnSummary::of).toList();
    }

    @Transactional(readOnly = true)
    public AsnDetail get(UUID id) {
        return detail(asns.findById(id).orElseThrow(() -> ApiException.notFound("ASN not found")));
    }

    @Transactional(readOnly = true)
    public AsnDetail getByNumber(String asnNumber, String owner) {
        String number = Codes.normalize(asnNumber);
        String wanted = Codes.normalize(owner);
        List<Asn> found = number == null ? List.of() : asns.findAllByAsnNumber(number).stream()
                .filter(a -> wanted == null || a.getOwner().getCode().equals(wanted)).toList();
        List<Asn> open = found.stream().filter(Asn::isOpen).toList();
        if (open.size() > 1) {
            String owners = open.stream().map(a -> a.getOwner().getCode()).sorted().collect(Collectors.joining(","));
            throw ApiException.conflict(number + " could be an ASN of " + owners.replace(",", ", ") + ". Say whose it is.", Map.of("owner", owners));
        }
        Asn asn = !open.isEmpty() ? open.getFirst() : found.isEmpty() ? null : found.getFirst();
        if (asn == null) {
            throw ApiException.invalid(number == null ? "Scan an ASN" : number + " is not a known ASN",
                    Map.of("asnNumber", "not found"));
        }
        if (!asn.isOpen()) {
            throw ApiException.invalid(number + (asn.getStatus() == AsnStatus.CANCELLED ? " is cancelled" : " is already closed"),
                    Map.of("asnNumber", "not open"));
        }
        return detail(asn);
    }

    public AsnDetail create(AsnRequest request) {
        String number = request.asnNumber() == null || request.asnNumber().isBlank()
                ? "AS%06d".formatted(asns.nextNumber())
                : Codes.normalize(request.asnNumber());
        Owner owner = owners.forNew(request.owner());
        if (asns.findByOwnerIdAndAsnNumber(owner.getId(), number).isPresent()) {
            throw ApiException.conflict("An ASN with this number already exists",
                    Map.of("asnNumber", "already used by another ASN"));
        }
        String reference = request.reference() == null || request.reference().isBlank() ? null : request.reference().trim();
        Asn asn = new Asn(UUID.randomUUID(), number, request.vendorName().trim(), reference, request.expectedDate());
        asn.setOwner(owner);

        Map<String, String> errors = new LinkedHashMap<>();
        Set<String> lpns = new HashSet<>();
        for (int i = 0; i < request.lines().size(); i++) {
            AsnRequest.Line line = request.lines().get(i);
            String prefix = "lines[" + i + "].";
            Item item = items.findByOwnerIdAndItemId(owner.getId(), Codes.normalize(line.itemId())).orElse(null);
            if (item == null) {
                errors.put(prefix + "itemId", "item " + line.itemId().trim() + " not found");
                continue;
            }
            if (item.getStatus() == ActiveStatus.INACTIVE) {
                errors.put(prefix + "itemId", item.getItemId() + " is inactive");
            }
            String lot = Codes.normalize(line.lotNumber());
            if (lot != null && !item.isLotControlled()) {
                errors.put(prefix + "lotNumber", item.getItemId() + " isn't lot-tracked");
            }
            if (line.expiryDate() != null && !item.isExpiryControlled()) {
                errors.put(prefix + "expiryDate", item.getItemId() + " isn't expiry-tracked");
            }
            String lpn = Codes.normalize(line.lpnId());
            if (lpn != null) {
                lpns.add(lpn);
                if (containers.findByLpnId(lpn).isPresent()) {
                    errors.put(prefix + "lpnId", lpn + " is already in the warehouse");
                } else {
                    newLpns.problemWith(lpn).ifPresent(problem -> errors.put(prefix + "lpnId", problem));
                }
            }
            asn.getLines().add(new AsnLine(UUID.randomUUID(), asn, i + 1, item, line.quantity(), lot, line.expiryDate(), lpn));
        }
        if (errors.isEmpty() && !lpns.isEmpty()) {
            List<String> announced = asns.findAnnouncedLpns(lpns);
            for (int i = 0; i < asn.getLines().size(); i++) {
                if (announced.contains(asn.getLines().get(i).getLpnId())) {
                    errors.put("lines[" + i + "].lpnId", "already announced on another open ASN");
                }
            }
        }
        if (!errors.isEmpty()) {
            throw ApiException.invalid("Some lines need attention", errors);
        }
        asn = asns.save(asn);
        long eaches = asn.getLines().stream().mapToLong(AsnLine::getExpectedQuantity).sum();
        events.save(new AsnEvent(asn, Type.CREATED, "ASN from %s: %d line%s, %d EA".formatted(asn.getVendorName(),
                asn.getLines().size(), asn.getLines().size() == 1 ? "" : "s", eaches)));
        asns.flush();
        return detail(asn);
    }

    /**
     * Starts receiving what nobody announced.
     *
     * @param id         made by the device, so starting it again changes nothing
     * @param vendorName who it is from, if known
     */
    public AsnDetail startBlind(UUID id, String ownerCode, String vendorName, String reference, String by) {
        if (id == null) {
            throw ApiException.invalid("A request ID is required", Map.of("requestId", "required"));
        }
        Asn known = asns.findById(id).orElse(null);
        if (known != null) {
            return detail(known);
        }
        String vendor = vendorName == null || vendorName.isBlank() ? "Vendor not known" : vendorName.trim();
        String ref = reference == null || reference.isBlank() ? null : reference.trim();
        if (vendor.length() > 120 || ref != null && ref.length() > 60) {
            throw ApiException.invalid("That is too long", vendor.length() > 120 ? Map.of("vendorName", "at most 120 characters")
                    : Map.of("reference", "at most 60 characters"));
        }
        Owner owner = owners.chosen(ownerCode);
        String number;
        do {
            number = "BR%06d".formatted(asns.nextBlindNumber());
        } while (asns.findByOwnerIdAndAsnNumber(owner.getId(), number).isPresent());
        Asn asn = Asn.blind(id, number, vendor, ref);
        asn.setOwner(owner);
        asn = asns.save(asn);
        events.save(new AsnEvent(asn, Type.CREATED, by + " started receiving without an ASN"));
        asns.flush();
        return detail(asn);
    }

    /** A blind receipt started for nothing. */
    public AsnDetail discardBlind(UUID id) {
        Asn asn = asns.findById(id).orElseThrow(() -> ApiException.notFound("ASN not found"));
        if (!asn.isBlind()) {
            throw ApiException.conflict(asn.getAsnNumber() + " was announced. Cancel it on the desktop.", Map.of());
        }
        return cancel(id);
    }

    /** It isn't coming. Only while nothing has been received. */
    public AsnDetail cancel(UUID id) {
        Asn asn = asns.findLocked(id).orElseThrow(() -> ApiException.notFound("ASN not found"));
        if (!asn.isOpen()) {
            throw ApiException.conflict(asn.getAsnNumber() + (asn.getStatus() == AsnStatus.CANCELLED
                    ? " is already cancelled." : " is already closed."), Map.of());
        }
        if (asn.anyReceived()) {
            throw ApiException.conflict(asn.getAsnNumber() + " can't be cancelled: some of it has been received. Close it instead.",
                    Map.of());
        }
        asn.cancel();
        events.save(new AsnEvent(asn, Type.CANCELLED, "ASN cancelled"));
        asns.flush();
        return detail(asn);
    }

    AsnDetail detail(Asn a) {
        return new AsnDetail(a.getId(), OwnerRef.of(a.getOwner()), a.getAsnNumber(), a.getVendorName(), a.getReference(), a.getExpectedDate(), a.getStatus(), a.isBlind(),
                a.getLines().stream().mapToLong(AsnLine::getExpectedQuantity).sum(),
                a.getLines().stream().mapToLong(AsnLine::getReceivedQuantity).sum(),
                a.getLines().stream().map(LineView::of).toList(),
                receipts.findByAsn_IdOrderByReceivedAtDescRequestPartAsc(a.getId()).stream().map(ReceiptView::of).toList(),
                events.findByAsn_IdOrderByOccurredAtAsc(a.getId()).stream().map(EventView::of).toList(),
                a.getClosedBy() == null ? null : a.getClosedBy().getDisplayName(), a.getCreatedAt(), a.getClosedAt());
    }

    /** An item on an ASN can't be deleted. */
    @Override
    @Transactional(readOnly = true)
    public Optional<String> describeUse(UUID itemId) {
        long count = asns.countLinesByItem(itemId);
        return count == 0 ? Optional.empty()
                : Optional.of(count == 1 ? "it is on 1 ASN line" : "it is on " + count + " ASN lines");
    }
}
