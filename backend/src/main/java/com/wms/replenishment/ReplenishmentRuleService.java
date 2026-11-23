package com.wms.replenishment;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wms.common.ActiveStatus;
import com.wms.common.api.ApiException;
import com.wms.masterdata.item.Item;
import com.wms.masterdata.item.ItemRepository;
import com.wms.masterdata.item.ItemUsage;
import com.wms.masterdata.location.Location;
import com.wms.masterdata.location.LocationRepository;
import com.wms.masterdata.location.LocationType;
import com.wms.masterdata.location.LocationUsage;
import com.wms.masterdata.zone.Zone;
import com.wms.masterdata.zone.ZoneFunction;
import com.wms.masterdata.zone.ZoneRepository;
import com.wms.masterdata.zone.ZoneUsage;
import com.wms.replenishment.ReplenishmentViews.RuleView;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

@Service
@Transactional
public class ReplenishmentRuleService {

    private final ReplenishmentRuleRepository rules;
    private final ReplenishmentTaskRepository tasks;
    private final Replenisher replenisher;
    private final ItemRepository items;
    private final LocationRepository locations;
    private final ZoneRepository zones;

    ReplenishmentRuleService(ReplenishmentRuleRepository rules, ReplenishmentTaskRepository tasks, Replenisher replenisher,
                             ItemRepository items, LocationRepository locations, ZoneRepository zones) {
        this.rules = rules;
        this.tasks = tasks;
        this.replenisher = replenisher;
        this.items = items;
        this.locations = locations;
        this.zones = zones;
    }

    /**
     * @param multiple     brought in multiples of this many eaches; 1 if left out
     * @param sourceZoneId a replenishment zone to bring it from; any other storage location if left out
     * @param forOrders    also fill for orders that are short of the item
     */
    public record RuleRequest(
            @NotNull UUID itemId,
            @NotNull UUID locationId,
            @NotNull @Min(0) @Max(1_000_000) Integer minQuantity,
            @NotNull @Min(1) @Max(1_000_000) Integer maxQuantity,
            @Min(1) @Max(1_000_000) Integer multiple,
            UUID sourceZoneId,
            Boolean forOrders,
            @NotNull ActiveStatus status,
            Long version) {
    }

    @Transactional(readOnly = true)
    public List<RuleView> list() {
        return rules.findAllByOrderByLocation_LocationIdAscItem_ItemIdAsc().stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public RuleView get(UUID id) {
        return view(load(id));
    }

    public RuleView create(RuleRequest request) {
        ReplenishmentRule rule = new ReplenishmentRule(UUID.randomUUID());
        apply(rule, request);
        return view(rules.saveAndFlush(rule));
    }

    /** Tasks of the rule that nobody has started are called off, and planned afresh if still needed. */
    public RuleView update(UUID id, RuleRequest request) {
        ReplenishmentRule rule = load(id);
        if (request.version() == null) {
            throw ApiException.invalid("Version is required when updating a rule", Map.of("version", "required"));
        }
        if (request.version() != rule.getVersion()) {
            throw ApiException.conflict("This rule was changed by someone else. Reload it and try again.", Map.of());
        }
        apply(rule, request);
        rules.saveAndFlush(rule);
        callOff(id, "The rule was changed");
        return view(rule);
    }

    public void delete(UUID id) {
        ReplenishmentRule rule = load(id);
        callOff(id, "The rule was deleted");
        rules.delete(rule);
    }

    private void callOff(UUID ruleId, String why) {
        tasks.findOpenOf(ruleId).forEach(t -> t.cancel(why));
        tasks.flush();
    }

    private void apply(ReplenishmentRule rule, RuleRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        Item item = items.findById(request.itemId()).orElse(null);
        if (item == null) {
            errors.put("itemId", "item not found");
        } else if (item.isSerialControlled()) {
            errors.put("itemId", item.getItemId() + " has serial numbers, which can't be replenished");
        }
        Location location = locations.findById(request.locationId()).orElse(null);
        if (location == null) {
            errors.put("locationId", "location not found");
        } else if (location.getType() != LocationType.STORAGE) {
            errors.put("locationId", location.getLocationId() + " is not a storage location");
        } else if (item != null) {
            rules.findByItem_IdAndLocation_Id(item.getId(), location.getId()).filter(other -> !other.getId().equals(rule.getId()))
                    .ifPresent(other -> errors.put("locationId", "already has a rule for " + item.getItemId()));
        }
        Zone zone = request.sourceZoneId() == null ? null
                : zones.findById(request.sourceZoneId()).filter(z -> z.getFunction() == ZoneFunction.REPLENISHMENT).orElse(null);
        if (request.sourceZoneId() != null && zone == null) {
            errors.put("sourceZoneId", "choose a replenishment zone");
        }
        int multiple = request.multiple() == null ? 1 : request.multiple();
        if (request.maxQuantity() <= request.minQuantity()) {
            errors.put("maxQuantity", "must be more than the minimum");
        } else if (request.maxQuantity() - request.minQuantity() < multiple) {
            errors.put("multiple", "must fit between the minimum and the maximum");
        }
        if (!errors.isEmpty()) {
            throw ApiException.invalid("Some fields need attention", errors);
        }
        rule.setItem(item);
        rule.setLocation(location);
        rule.setMinQuantity(request.minQuantity());
        rule.setMaxQuantity(request.maxQuantity());
        rule.setMultiple(multiple);
        rule.setSourceZone(zone);
        rule.setForOrders(Boolean.TRUE.equals(request.forOrders()));
        rule.setStatus(request.status());
    }

    private ReplenishmentRule load(UUID id) {
        return rules.findById(id).orElseThrow(() -> ApiException.notFound("Replenishment rule not found"));
    }

    private RuleView view(ReplenishmentRule rule) {
        return RuleView.of(rule, rule.getStatus() == ActiveStatus.ACTIVE ? replenisher.level(rule.getId()) : null);
    }

    /** Items, locations and zones that rules or tasks name can't be deleted. */
    @Component
    static class OfZone implements ZoneUsage {
        private final ReplenishmentRuleRepository rules;

        OfZone(ReplenishmentRuleRepository rules) {
            this.rules = rules;
        }

        @Override
        public Optional<String> describeUse(UUID id) {
            return use(rules.countBySourceZone_Id(id), 0);
        }
    }

    @Component
    static class OfItem implements ItemUsage {
        private final ReplenishmentRuleRepository rules;
        private final ReplenishmentTaskRepository tasks;

        OfItem(ReplenishmentRuleRepository rules, ReplenishmentTaskRepository tasks) {
            this.rules = rules;
            this.tasks = tasks;
        }

        @Override
        public Optional<String> describeUse(UUID id) {
            return use(rules.countByItem_Id(id), tasks.countByItem_Id(id));
        }
    }

    @Component
    static class AtLocation implements LocationUsage {
        private final ReplenishmentRuleRepository rules;
        private final ReplenishmentTaskRepository tasks;

        AtLocation(ReplenishmentRuleRepository rules, ReplenishmentTaskRepository tasks) {
            this.rules = rules;
            this.tasks = tasks;
        }

        @Override
        public Optional<String> describeUse(UUID id) {
            return use(rules.countByLocation_Id(id), tasks.countAt(id));
        }
    }

    private static Optional<String> use(long rules, long tasks) {
        if (rules > 0) {
            return Optional.of(rules == 1 ? "1 replenishment rule uses it" : rules + " replenishment rules use it");
        }
        return tasks == 0 ? Optional.empty() : Optional.of("there are replenishment tasks for it");
    }
}
