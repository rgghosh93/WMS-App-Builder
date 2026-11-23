package com.wms.replenishment;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.wms.common.ActiveStatus;
import com.wms.inventory.AllocationLookup;
import com.wms.inventory.InventoryRecord;
import com.wms.inventory.InventoryRepository;
import com.wms.inventory.LocationLocks;
import com.wms.inventory.StockLedger;
import com.wms.inventory.WaitingOrders;
import com.wms.masterdata.location.Location;
import com.wms.masterdata.location.LocationType;
import com.wms.masterdata.zone.Zone;
import com.wms.masterdata.zone.ZoneFunction;
import com.wms.masterdata.zone.Zones;
import com.wms.putaway.StorageSpace;
import com.wms.replenishment.ReplenishmentTask.Reason;

/** Works out what a pick location needs, and makes tasks to bring it. */
@Component
class Replenisher {

    /**
     * How a rule's pick location stands.
     *
     * @param onHand   good stock of the item there
     * @param reserved of that, for orders or other moves
     * @param coming   on its way there
     * @param waiting  orders are short of this much, if it is filled for them
     * @param needed   to bring, over and above what is coming
     */
    record Level(int onHand, int reserved, int coming, int waiting, int needed) {
    }

    private final ReplenishmentControlRepository control;
    private final ReplenishmentRuleRepository rules;
    private final ReplenishmentTaskRepository tasks;
    private final ReplenishmentStockRepository stock;
    private final List<AllocationLookup> reservations;
    private final WaitingOrders orders;
    private final LocationLocks locks;
    private final Zones zones;
    private final InventoryRepository inventory;
    private final StorageSpace space;

    Replenisher(ReplenishmentControlRepository control, ReplenishmentRuleRepository rules, ReplenishmentTaskRepository tasks,
                ReplenishmentStockRepository stock, List<AllocationLookup> reservations, WaitingOrders orders, LocationLocks locks, Zones zones,
                InventoryRepository inventory, StorageSpace space) {
        this.control = control;
        this.rules = rules;
        this.tasks = tasks;
        this.stock = stock;
        this.reservations = reservations;
        this.orders = orders;
        this.locks = locks;
        this.zones = zones;
        this.inventory = inventory;
        this.space = space;
    }

    @Transactional(readOnly = true)
    public Level level(UUID ruleId) {
        ReplenishmentRule rule = rules.findById(ruleId).orElseThrow();
        return level(rule, stock.find(rule.getItem().getId(), LocalDate.now()));
    }

    /** Calls off tasks of a rule whose stock can't be used any more. */
    @Transactional
    public void tidy(UUID ruleId) {
        for (ReplenishmentTask t : tasks.findOpenOf(ruleId)) {
            if (inventory.findById(t.getInventoryId()).filter(InventoryRecord::isUsable).isEmpty()) {
                t.cancel("The stock can't be used any more");
            }
        }
    }

    /** @return eaches that tasks were made for */
    @Transactional
    public int plan(UUID ruleId) {
        control.lock();
        ReplenishmentRule rule = rules.findById(ruleId).orElse(null);
        if (rule == null || rule.getStatus() != ActiveStatus.ACTIVE || rule.getItem().getStatus() != ActiveStatus.ACTIVE
                || rule.getItem().isSerialControlled() || rule.getLocation().getType() != LocationType.STORAGE
                || locks.isLocked(rule.getLocation().getId())) {
            return 0;
        }
        UUID itemId = rule.getItem().getId();
        List<InventoryRecord> found = stock.findLocked(itemId, LocalDate.now());
        Level level = level(rule, found);
        if (level.needed() == 0) {
            return 0;
        }
        Reason reason = level.waiting() > 0 ? Reason.ORDERS_WAITING : level.onHand() == 0 ? Reason.EMPTY : Reason.BELOW_MINIMUM;
        // Pick locations of the same item don't fill one another.
        Set<UUID> skip = new HashSet<>();
        rules.findByItem_IdAndStatusOrderByLocation_LocationIdAsc(itemId, ActiveStatus.ACTIVE).forEach(r -> skip.add(r.getLocation().getId()));
        skip.add(rule.getLocation().getId());
        Set<UUID> from = new HashSet<>();
        if (rule.getSourceZone() != null) {
            zones.locations(rule.getSourceZone()).forEach(l -> from.add(l.getId()));
        }
        List<InventoryRecord> sources = new ArrayList<>();
        for (InventoryRecord r : found) {
            Location at = StockLedger.locationOf(r);
            if (at != null && at.getType() == LocationType.STORAGE && !skip.contains(at.getId())
                    && (rule.getSourceZone() == null || from.contains(at.getId()))) {
                sources.add(r);
            }
        }
        Set<UUID> locked = sources.isEmpty() ? Set.of()
                : locks.locked(sources.stream().map(r -> StockLedger.locationOf(r).getId()).distinct().toList());
        Map<UUID, Integer> reserved = reserved(sources);
        int multiple = rule.getMultiple();
        // No more than the location can bear, with what is coming. The rest is brought once some is picked.
        int bearable = Math.max(0, space.mostByWeight(rule.getItem(), rule.getLocation()) - level.coming());
        int planned = Math.min(level.needed(), bearable);
        int left = planned;
        for (InventoryRecord r : sources) {
            Location at = StockLedger.locationOf(r);
            if (left < multiple || locked.contains(at.getId())) {
                continue;
            }
            int free = r.getQuantity() - reserved.getOrDefault(r.getId(), 0);
            // Whole multiples, except for what is left of less than one.
            int take = free < multiple ? Math.max(free, 0) : Math.min(free, left) / multiple * multiple;
            if (take > 0) {
                tasks.save(new ReplenishmentTask("RP%06d".formatted(tasks.nextNumber()), rule, reason, r, at, take));
                left -= take;
            }
        }
        tasks.flush();
        return planned - left;
    }

    /** @param good all good stock of the item */
    private Level level(ReplenishmentRule rule, List<InventoryRecord> good) {
        UUID itemId = rule.getItem().getId();
        UUID locationId = rule.getLocation().getId();
        List<InventoryRecord> there = good.stream().filter(r -> {
            Location at = StockLedger.locationOf(r);
            return at != null && at.getId().equals(locationId);
        }).toList();
        int onHand = there.stream().mapToInt(InventoryRecord::getQuantity).sum();
        int reserved = reserved(there).values().stream().mapToInt(Integer::intValue).sum();
        int coming = 0;
        for (Object[] row : tasks.sumComing(List.of(itemId))) {
            if (locationId.equals(row[1])) {
                coming += ((Number) row[2]).intValue();
            }
        }
        Zone allocation = zones.of(ZoneFunction.ALLOCATION, List.of(locationId)).get(locationId);
        boolean forOrders = rule.isForOrders() && (allocation == null || allocation.isAllocatable()) && isFirstForOrders(rule);
        int waiting = forOrders ? orders.shortOf(itemId) : 0;
        int projected = onHand - reserved + coming - waiting;
        int needed = projected < rule.getMinQuantity() ? (rule.getMaxQuantity() - projected) / rule.getMultiple() * rule.getMultiple() : 0;
        return new Level(onHand, reserved, coming, waiting, needed);
    }

    /** Orders are filled for at one of an item's pick locations, or the stock would be brought more than once. */
    private boolean isFirstForOrders(ReplenishmentRule rule) {
        return rules.findByItem_IdAndStatusOrderByLocation_LocationIdAsc(rule.getItem().getId(), ActiveStatus.ACTIVE).stream()
                .filter(ReplenishmentRule::isForOrders).findFirst().filter(r -> r.getId().equals(rule.getId())).isPresent();
    }

    private Map<UUID, Integer> reserved(Collection<InventoryRecord> records) {
        Map<UUID, Integer> result = new HashMap<>();
        if (!records.isEmpty()) {
            List<UUID> ids = records.stream().map(InventoryRecord::getId).toList();
            reservations.forEach(l -> l.allocatedByInventory(ids).forEach((id, n) -> result.merge(id, n, Integer::sum)));
        }
        return result;
    }
}
