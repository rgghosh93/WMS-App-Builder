package com.wms.putaway;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.wms.inventory.InventoryRecord;
import com.wms.masterdata.container.ContainerType;
import com.wms.masterdata.item.Item;
import com.wms.masterdata.location.Location;

/** What a location holds, with what is on its way there, against what it can hold. */
final class Occupancy {

    /** @param hard never allowed, not even by choice */
    record Problem(String message, boolean hard) {
    }

    private int lpns;
    private long eaches;
    private double weight;
    private double volume;
    private final Set<UUID> items = new HashSet<>();
    private final Set<Load.Kind> kinds = new HashSet<>();

    /** Stock lying loose. */
    void addLoose(InventoryRecord r) {
        addContents(r);
        weight += Load.weight(r.getItem()) * r.getQuantity();
        volume += Load.volume(r.getItem()) * r.getQuantity();
    }

    void add(Load load, PutawayHandling handling) {
        if (handling == PutawayHandling.KEEP_LPN) {
            lpns++;
        }
        load.stock().forEach(this::addContents);
        weight += load.weight(handling);
        volume += load.volume(handling);
    }

    private void addContents(InventoryRecord r) {
        eaches += r.getQuantity();
        items.add(r.getItem().getId());
        kinds.add(Load.Kind.of(r));
    }

    boolean isEmpty() {
        return lpns == 0 && eaches == 0;
    }

    /** Whether it holds only stock the load also has, so nothing gets mixed. */
    boolean holdsOnly(Set<Load.Kind> wanted) {
        return !kinds.isEmpty() && wanted.containsAll(kinds);
    }

    /** How many more eaches of an item the location can bear. */
    int mostByWeight(Location l, Item item) {
        double each = Load.weight(item);
        if (l.getMaxWeight() == null || each <= 0) {
            return Integer.MAX_VALUE;
        }
        double max = l.getMaxWeightUom().toPounds(l.getMaxWeight().doubleValue());
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, Math.floor((max * 1.0001 - weight) / each)));
    }

    /** Why the load doesn't fit; empty if it does. */
    List<Problem> problems(Location l, Load load, PutawayHandling handling) {
        List<Problem> problems = new ArrayList<>();
        if (l.getMaxWeight() != null) {
            double max = l.getMaxWeightUom().toPounds(l.getMaxWeight().doubleValue());
            double total = weight + load.weight(handling);
            if (total > max * 1.0001) {
                problems.add(new Problem("%s can't take the weight: it would hold %,.0f lb, and its limit is %,.0f lb".formatted(
                        l.getLocationId(), total, max), true));
            }
        }
        if (handling == PutawayHandling.KEEP_LPN && l.getMaxLpns() != null && lpns + 1 > l.getMaxLpns()) {
            problems.add(new Problem("%s has no room for another LPN (it holds %d)".formatted(l.getLocationId(), l.getMaxLpns()), false));
        }
        if (l.getMaxVolume() != null) {
            double max = l.getMaxVolumeUom().toCubicInches(l.getMaxVolume().doubleValue());
            double total = volume + load.volume(handling);
            if (total > max * 1.0001) {
                problems.add(new Problem("%s is too full: it would hold %,.1f cu ft, and its limit is %,.1f cu ft".formatted(
                        l.getLocationId(), total / 1728, max / 1728), false));
            }
        }
        if (l.getMaxUnits() != null && eaches + load.eaches() > l.getMaxUnits()) {
            problems.add(new Problem("%s takes at most %,d EA, and it would hold %,d".formatted(
                    l.getLocationId(), l.getMaxUnits(), eaches + load.eaches()), false));
        }
        if (l.getMaxSkus() != null) {
            Set<UUID> all = new HashSet<>(items);
            all.addAll(load.itemIds());
            if (all.size() > l.getMaxSkus()) {
                problems.add(new Problem("%s takes at most %d item%s, and it would hold %d".formatted(
                        l.getLocationId(), l.getMaxSkus(), l.getMaxSkus() == 1 ? "" : "s", all.size()), false));
            }
        }
        if (handling == PutawayHandling.KEEP_LPN && !fits(l, load.lpn().getContainerType())) {
            problems.add(new Problem("%s is too big for %s".formatted(load.lpn().getLpnId(), l.getLocationId()), false));
        }
        return problems;
    }

    /** Whether the container itself goes in, turned either way. */
    private static boolean fits(Location l, ContainerType t) {
        double lw = l.getDimensionUom().toInches(l.getWidth().doubleValue());
        double ld = l.getDimensionUom().toInches(l.getDepth().doubleValue());
        double lh = l.getDimensionUom().toInches(l.getHeight().doubleValue());
        double cw = t.getDimensionUom().toInches(t.getWidth().doubleValue());
        double cd = t.getDimensionUom().toInches(t.getDepth().doubleValue());
        double ch = t.getDimensionUom().toInches(t.getHeight().doubleValue());
        double slack = 0.01;
        return ch <= lh + slack && Math.min(cw, cd) <= Math.min(lw, ld) + slack && Math.max(cw, cd) <= Math.max(lw, ld) + slack;
    }
}
