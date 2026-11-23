package com.wms.control;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wms.common.Codes;
import com.wms.control.ControlViews.AtFacility;
import com.wms.control.ControlViews.ItemOnHand;
import com.wms.control.ControlViews.ItemRef;
import com.wms.control.ControlViews.OnHand;
import com.wms.control.ControlViews.StockView;
import com.wms.control.ControlViews.TransactionView;
import com.wms.inventory.AllocationLookup;
import com.wms.inventory.InventoryRecord;
import com.wms.inventory.InventoryStatus;
import com.wms.inventory.StockHistory;
import com.wms.inventory.StockLedger;
import com.wms.inventory.StockTransaction;
import com.wms.masterdata.item.Item;
import com.wms.masterdata.location.Location;
import com.wms.masterdata.location.LocationType;
import com.wms.masterdata.zone.ZoneFunction;
import com.wms.masterdata.zone.Zones;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;

/** Looking at stock: record by record, by item and lot, and through time. */
@Service
@Transactional(readOnly = true)
public class StockQueryService {

    private static final String MATCHES = """
             (lower(i.item.itemId) like :q escape '\\' or lower(i.item.description) like :q escape '\\' or lower(i.lotNumber) like :q escape '\\'
              or lower(i.serialNumber) like :q escape '\\' or lower(l.locationId) like :q escape '\\' or lower(c.lpnId) like :q escape '\\'
              or lower(cl.locationId) like :q escape '\\' or lower(i.holdReason) like :q escape '\\')""";
    private static final String FROM = """
             from InventoryRecord i left join i.location l left join i.container c left join c.currentLocation cl
             where """;

    private final EntityManager em;
    private final List<AllocationLookup> reservations;
    private final StockHistory history;
    private final Zones zones;

    StockQueryService(EntityManager em, List<AllocationLookup> reservations, StockHistory history, Zones zones) {
        this.em = em;
        this.reservations = reservations;
        this.history = history;
        this.zones = zones;
    }

    /**
     * @param held  only stock on hold or damaged
     * @param owner only of this owner; all if null
     */
    public Page<StockView> stock(String q, boolean held, UUID owner, Pageable page) {
        String where = MATCHES + (held ? " and i.status <> com.wms.inventory.InventoryStatus.AVAILABLE" : "");
        String like = like(q);
        // With nothing to search for, stock without a lot or an LPN must match too.
        boolean all = q == null || q.isBlank();
        String filter = (all ? (held ? "i.status <> com.wms.inventory.InventoryStatus.AVAILABLE" : "1 = 1") : where)
                + (owner == null ? "" : " and i.item.ownerId = :owner");
        TypedQuery<InventoryRecord> query = em.createQuery("select i " + FROM + " " + filter
                + (held ? " order by i.heldAt desc nulls last, i.item.itemId, i.id" : " order by i.item.itemId, i.lotNumber, i.id"), InventoryRecord.class);
        TypedQuery<Long> count = em.createQuery("select count(i) " + FROM + " " + filter, Long.class);
        if (!all) {
            query.setParameter("q", like);
            count.setParameter("q", like);
        }
        if (owner != null) {
            query.setParameter("owner", owner);
            count.setParameter("owner", owner);
        }
        List<InventoryRecord> found = query.setFirstResult((int) page.getOffset()).setMaxResults(page.getPageSize()).getResultList();
        Map<UUID, Integer> reserved = reserved(found);
        return new PageImpl<>(found.stream().map(r -> StockView.of(r, reserved.getOrDefault(r.getId(), 0))).toList(), page,
                count.getSingleResult());
    }

    StockView view(InventoryRecord r) {
        return StockView.of(r, reserved(List.of(r)).getOrDefault(r.getId(), 0));
    }

    Map<UUID, Integer> reserved(Collection<InventoryRecord> records) {
        Map<UUID, Integer> result = new HashMap<>();
        List<UUID> ids = records.stream().map(InventoryRecord::getId).toList();
        reservations.forEach(l -> l.allocatedByInventory(ids).forEach((id, n) -> result.merge(id, n, Integer::sum)));
        return result;
    }

    // ---------- On hand ----------

    /**
     * Items there is stock of, with their lots.
     *
     * @param owner      only of this owner; all if null
     * @param facilities to look at all of these at once, by id; null for the current one. Takes {@link com.wms.facility.Site#everywhere}
     */
    public Page<ItemOnHand> onHand(String q, UUID owner, Map<UUID, String> facilities, Pageable page) {
        boolean all = q == null || q.isBlank();
        String where = " from Item t where exists (select 1 from InventoryRecord i where i.item = t"
                + (facilities == null ? "" : " and i.facilityId in :facilities") + ")"
                + (owner == null ? "" : " and t.ownerId = :owner") + (all ? ""
                : " and (lower(t.itemId) like :q escape '\\' or lower(t.description) like :q escape '\\'"
                        + " or exists (select 1 from InventoryRecord i where i.item = t and lower(i.lotNumber) like :q escape '\\'))");
        TypedQuery<Item> query = em.createQuery("select t" + where + " order by t.itemId", Item.class);
        TypedQuery<Long> count = em.createQuery("select count(t)" + where, Long.class);
        if (!all) {
            query.setParameter("q", like(q));
            count.setParameter("q", like(q));
        }
        if (owner != null) {
            query.setParameter("owner", owner);
            count.setParameter("owner", owner);
        }
        if (facilities != null) {
            query.setParameter("facilities", facilities.keySet());
            count.setParameter("facilities", facilities.keySet());
        }
        List<Item> items = query.setFirstResult((int) page.getOffset()).setMaxResults(page.getPageSize()).getResultList();
        List<InventoryRecord> stock = items.isEmpty() ? List.of()
                : em.createQuery("select i from InventoryRecord i where i.item in :items", InventoryRecord.class)
                        .setParameter("items", items).getResultStream()
                        .filter(r -> facilities == null || facilities.containsKey(r.getFacilityId())).toList();
        Map<UUID, Integer> reserved = reserved(stock);
        Set<UUID> kept = zones.of(ZoneFunction.ALLOCATION, stock.stream().map(StockLedger::locationOf).filter(Objects::nonNull)
                .map(Location::getId).collect(Collectors.toSet())).entrySet().stream()
                .filter(e -> !e.getValue().isAllocatable()).map(Map.Entry::getKey).collect(Collectors.toSet());
        Map<UUID, List<InventoryRecord>> byItem = stock.stream().collect(Collectors.groupingBy(r -> r.getItem().getId()));
        List<ItemOnHand> result = new ArrayList<>();
        for (Item item : items) {
            List<InventoryRecord> of = byItem.getOrDefault(item.getId(), List.of());
            Map<List<Object>, List<InventoryRecord>> lots = of.stream()
                    .filter(r -> r.getLotNumber() != null || r.getExpiryDate() != null)
                    .collect(Collectors.groupingBy(r -> java.util.Arrays.asList(r.getLotNumber(), r.getExpiryDate()), LinkedHashMap::new,
                            Collectors.toList()));
            result.add(new ItemOnHand(ItemRef.of(item), sum(null, null, of, reserved, kept), lots.values().stream()
                    .map(l -> sum(l.getFirst().getLotNumber(), l.getFirst().getExpiryDate(), l, reserved, kept))
                    .sorted(Comparator.comparing(OnHand::expiryDate, Comparator.nullsLast(Comparator.naturalOrder()))
                            .thenComparing(OnHand::lotNumber, Comparator.nullsLast(Comparator.naturalOrder()))).toList(),
                    facilities == null ? null : facilities.entrySet().stream().sorted(Map.Entry.comparingByValue())
                            .map(f -> new AtFacility(f.getValue(), sum(null, null, of.stream()
                                    .filter(r -> r.getFacilityId().equals(f.getKey())).toList(), reserved, kept)))
                            .filter(f -> f.total().total() > 0).toList()));
        }
        return new PageImpl<>(result, page, count.getSingleResult());
    }

    /** @param kept locations orders get nothing from */
    private static OnHand sum(String lot, LocalDate expiry, List<InventoryRecord> records, Map<UUID, Integer> reservedBy,
                              Set<UUID> kept) {
        long total = 0;
        long available = 0;
        long reserved = 0;
        long onHold = 0;
        long damaged = 0;
        long expired = 0;
        long elsewhere = 0;
        for (InventoryRecord r : records) {
            int held = reservedBy.getOrDefault(r.getId(), 0);
            int free = r.getQuantity() - held;
            total += r.getQuantity();
            reserved += held;
            Location at = StockLedger.locationOf(r);
            if (r.getStatus() == InventoryStatus.ON_HOLD) {
                onHold += free;
            } else if (r.getStatus() == InventoryStatus.DAMAGED) {
                damaged += free;
            } else if (r.isExpired()) {
                expired += free;
            } else if (at != null && at.getType() == LocationType.STORAGE && !kept.contains(at.getId())) {
                available += free;
            } else {
                elsewhere += free;
            }
        }
        return new OnHand(lot, expiry, total, available, reserved, onHold, damaged, expired, elsewhere);
    }

    // ---------- History ----------

    /** Newest first. */
    public Page<TransactionView> history(String q, String owner, StockTransaction.Type type, LocalDate from, LocalDate to, int page, int size) {
        Instant since = from == null ? null : from.atStartOfDay(ZoneId.systemDefault()).toInstant();
        Instant before = to == null ? null : to.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant();
        return history.search(q, Codes.normalize(owner), type, since, before, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "number")))
                .map(TransactionView::of);
    }

    private static String like(String q) {
        return q == null ? "%" : "%" + q.trim().toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }
}
