package com.wms.putaway;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
import com.wms.putaway.PutawayViews.RuleView;

@Service
@Transactional
public class PutawayRuleService {

    private final PutawayRuleRepository rules;
    private final ItemRepository items;
    private final LocationRepository locations;
    private final ZoneRepository zoneRepository;

    PutawayRuleService(PutawayRuleRepository rules, ItemRepository items, LocationRepository locations, ZoneRepository zoneRepository) {
        this.rules = rules;
        this.items = items;
        this.locations = locations;
        this.zoneRepository = zoneRepository;
    }

    @Transactional(readOnly = true)
    public List<RuleView> list() {
        return rules.findAllByOrderBySequenceAscNameAsc().stream().map(RuleView::of).toList();
    }

    @Transactional(readOnly = true)
    public RuleView get(UUID id) {
        return RuleView.of(load(id));
    }

    public RuleView create(PutawayRuleRequest request) {
        PutawayRule rule = new PutawayRule(UUID.randomUUID());
        apply(rule, request);
        return RuleView.of(rules.saveAndFlush(rule));
    }

    public RuleView update(UUID id, PutawayRuleRequest request) {
        PutawayRule rule = load(id);
        if (request.version() == null) {
            throw ApiException.invalid("Version is required when updating a rule", Map.of("version", "required"));
        }
        if (request.version() != rule.getVersion()) {
            throw ApiException.conflict("This rule was changed by someone else. Reload it and try again.", Map.of());
        }
        apply(rule, request);
        return RuleView.of(rules.saveAndFlush(rule));
    }

    public void delete(UUID id) {
        rules.delete(load(id));
    }

    private void apply(PutawayRule rule, PutawayRuleRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        String name = request.name().trim();
        rules.findFirstByNameIgnoreCase(name).filter(other -> !other.getId().equals(rule.getId()))
                .ifPresent(other -> errors.put("name", "already used by another rule"));
        Item item = request.itemId() == null ? null : items.findById(request.itemId()).orElse(null);
        if (request.itemId() != null && item == null) {
            errors.put("itemId", "item not found");
        }
        Location location = null;
        Zone zone = null;
        if (request.strategy() == PutawayStrategy.FIXED) {
            location = request.locationId() == null ? null : locations.findById(request.locationId()).orElse(null);
            if (location == null) {
                errors.put("locationId", "choose a location");
            } else if (location.getType() != LocationType.STORAGE) {
                errors.put("locationId", location.getLocationId() + " is not a storage location");
            }
        } else if (request.zoneId() != null) {
            zone = zoneRepository.findById(request.zoneId()).filter(z -> z.getFunction() == ZoneFunction.PUTAWAY).orElse(null);
        }
        if (request.strategy() != PutawayStrategy.FIXED && zone == null
                && (request.strategy() == PutawayStrategy.ZONE || request.zoneId() != null)) {
            errors.put("zoneId", "choose a put-away zone");
        }
        if (!errors.isEmpty()) {
            throw ApiException.invalid("Some fields need attention", errors);
        }
        rule.setSequence(request.sequence());
        rule.setName(name);
        rule.setStatus(request.status());
        rule.setItem(item);
        rule.setContainerCategory(request.containerCategory());
        rule.setStockStatus(request.stockStatus());
        rule.setStrategy(request.strategy());
        rule.setLocation(location);
        rule.setZone(zone);
        rule.setHandling(request.handling());
    }

    private PutawayRule load(UUID id) {
        return rules.findById(id).orElseThrow(() -> ApiException.notFound("Put-away rule not found"));
    }

    /** Items, locations and zones that rules name can't be deleted. */
    @Component
    static class Usages implements ItemUsage, LocationUsage, ZoneUsage {
        private final PutawayRuleRepository rules;

        Usages(PutawayRuleRepository rules) {
            this.rules = rules;
        }

        @Override
        public Optional<String> describeUse(UUID id) {
            long count = rules.countByItem_Id(id) + rules.countByLocation_Id(id) + rules.countByZone_Id(id);
            return count == 0 ? Optional.empty()
                    : Optional.of(count == 1 ? "1 put-away rule uses it" : count + " put-away rules use it");
        }
    }
}
