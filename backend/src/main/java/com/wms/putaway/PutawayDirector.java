package com.wms.putaway;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.wms.common.ActiveStatus;
import com.wms.inventory.InventoryRecord;
import com.wms.inventory.InventoryRepository;
import com.wms.inventory.LocationLocks;
import com.wms.masterdata.container.Container;
import com.wms.masterdata.container.ContainerRepository;
import com.wms.masterdata.location.Location;
import com.wms.masterdata.location.LocationRepository;
import com.wms.masterdata.location.LocationType;
import com.wms.masterdata.zone.Zones;

/** Works out where an LPN should go, by the rules and what there is room for. */
@Component
class PutawayDirector {

    /** @param location null if no rule found room */
    record Direction(Location location, String ruleName, PutawayHandling handling) {
    }

    private final PutawayRuleRepository rules;
    private final PutawayTaskRepository tasks;
    private final LocationRepository locations;
    private final ContainerRepository containers;
    private final InventoryRepository inventory;
    private final LocationLocks locks;
    private final Zones zones;

    PutawayDirector(PutawayRuleRepository rules, PutawayTaskRepository tasks, LocationRepository locations,
                    ContainerRepository containers, InventoryRepository inventory, LocationLocks locks, Zones zones) {
        this.rules = rules;
        this.tasks = tasks;
        this.locations = locations;
        this.containers = containers;
        this.inventory = inventory;
        this.locks = locks;
        this.zones = zones;
    }

    /** @param avoid locations already found not to be usable */
    Direction direct(PutawayTask task, Load load, Set<UUID> avoid) {
        for (PutawayRule rule : rules.findByStatusOrderBySequenceAscNameAsc(ActiveStatus.ACTIVE)) {
            if (!matches(rule, load)) {
                continue;
            }
            // An LPN with other containers in it can't be emptied.
            PutawayHandling handling = load.nested().isEmpty() ? rule.getHandling() : PutawayHandling.KEEP_LPN;
            List<Location> allowed = candidates(rule);
            Set<UUID> locked = locks.locked(allowed.stream().map(Location::getId).toList());
            List<Location> candidates = allowed.stream().filter(l -> !avoid.contains(l.getId()) && !locked.contains(l.getId())).toList();
            Map<UUID, Occupancy> held = occupancy(candidates, task);
            List<Location> room = candidates.stream()
                    .filter(l -> held.get(l.getId()).problems(l, load, handling).isEmpty()).toList();
            Location chosen = choose(rule.getStrategy(), room, held, load);
            if (chosen != null) {
                return new Direction(chosen, rule.getName(), handling);
            }
        }
        PutawayHandling handling = load.lpn().isPermanent() && load.nested().isEmpty()
                ? PutawayHandling.EMPTY_LPN : PutawayHandling.KEEP_LPN;
        return new Direction(null, null, handling);
    }

    private static boolean matches(PutawayRule rule, Load load) {
        return (rule.getItem() == null || load.itemIds().equals(Set.of(rule.getItem().getId())))
                && (rule.getContainerCategory() == null
                        || rule.getContainerCategory() == load.lpn().getContainerType().getCategory())
                && (rule.getStockStatus() == null
                        || load.stock().stream().anyMatch(r -> r.getStatus() == rule.getStockStatus()));
    }

    private List<Location> candidates(PutawayRule rule) {
        if (rule.getStrategy() == PutawayStrategy.FIXED) {
            return rule.getLocation().getType() == LocationType.STORAGE ? List.of(rule.getLocation()) : List.of();
        }
        return rule.getZone() == null ? locations.findByTypeOrderByLocationId(LocationType.STORAGE)
                : zones.locations(rule.getZone());
    }

    private static Location choose(PutawayStrategy strategy, List<Location> room, Map<UUID, Occupancy> held, Load load) {
        Location empty = room.stream().filter(l -> held.get(l.getId()).isEmpty()).findFirst().orElse(null);
        return switch (strategy) {
            case FIXED -> room.isEmpty() ? null : room.getFirst();
            case NEAREST_EMPTY -> empty;
            case ZONE -> {
                Set<Load.Kind> kinds = load.kinds();
                Location same = room.stream().filter(l -> held.get(l.getId()).holdsOnly(kinds)).findFirst().orElse(null);
                yield same != null ? same : empty != null ? empty : room.isEmpty() ? null : room.getFirst();
            }
        };
    }

    /** What each location holds, and what others are bringing to it. @param except a task to leave out, or null */
    Map<UUID, Occupancy> occupancy(Collection<Location> where, PutawayTask except) {
        Map<UUID, Occupancy> result = new HashMap<>();
        where.forEach(l -> result.put(l.getId(), new Occupancy()));
        if (where.isEmpty()) {
            return result;
        }
        for (InventoryRecord r : inventory.findByLocation_IdIn(result.keySet())) {
            result.get(r.getLocation().getId()).addLoose(r);
        }
        List<Container> there = containers.findByCurrentLocation_IdIn(result.keySet());
        for (Map.Entry<Container, Load> e : loads(there).entrySet()) {
            result.get(e.getKey().getCurrentLocation().getId()).add(e.getValue(), PutawayHandling.KEEP_LPN);
        }
        List<PutawayTask> coming = tasks.findHeadingFor(result.keySet()).stream()
                .filter(t -> except == null || !t.getId().equals(except.getId())).toList();
        Map<Container, Load> bringing = loads(coming.stream().map(PutawayTask::getContainer).toList());
        for (PutawayTask t : coming) {
            result.get(t.getDirectedLocation().getId()).add(bringing.get(t.getContainer()), t.getHandling());
        }
        return result;
    }

    Load load(Container lpn) {
        return loads(List.of(lpn)).get(lpn);
    }

    /** Containers with everything in them, however deep. */
    Map<Container, Load> loads(Collection<Container> lpns) {
        Map<UUID, Load> byContainer = new HashMap<>();
        Map<Container, Load> result = new HashMap<>();
        List<Container> level = new ArrayList<>(new LinkedHashSet<>(lpns));
        for (Container c : level) {
            Load load = new Load(c, new ArrayList<>(), new ArrayList<>());
            byContainer.put(c.getId(), load);
            result.put(c, load);
        }
        while (!level.isEmpty()) {
            Set<UUID> ids = level.stream().map(Container::getId).collect(Collectors.toSet());
            for (InventoryRecord r : inventory.findByContainer_IdIn(ids)) {
                byContainer.get(r.getContainer().getId()).stock().add(r);
            }
            level = containers.findByParent_IdIn(ids);
            for (Container child : level) {
                Load load = byContainer.get(child.getParent().getId());
                load.nested().add(child);
                byContainer.put(child.getId(), load);
            }
        }
        return result;
    }
}
