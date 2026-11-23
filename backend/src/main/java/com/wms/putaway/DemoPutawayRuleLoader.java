package com.wms.putaway;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.wms.common.ActiveStatus;
import com.wms.masterdata.container.ContainerCategory;
import com.wms.masterdata.item.Item;
import com.wms.masterdata.item.ItemRepository;
import com.wms.masterdata.location.Location;
import com.wms.masterdata.location.LocationRepository;
import com.wms.masterdata.zone.ZoneFunction;
import com.wms.masterdata.zone.ZoneService;

/** Demo put-away rules for the demo warehouse, when there are none. */
@Component
@ConditionalOnProperty(name = "wms.demo-data.enabled", havingValue = "true")
@Order(40)
public class DemoPutawayRuleLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoPutawayRuleLoader.class);

    private final PutawayRuleRepository rules;
    private final PutawayRuleService service;
    private final ItemRepository items;
    private final LocationRepository locations;
    private final ZoneService zones;

    public DemoPutawayRuleLoader(PutawayRuleRepository rules, PutawayRuleService service, ItemRepository items,
                                 LocationRepository locations, ZoneService zones) {
        this.rules = rules;
        this.service = service;
        this.items = items;
        this.locations = locations;
        this.zones = zones;
    }

    @Override
    public void run(ApplicationArguments args) {
        Item item = items.findByItemId("SKU-10004").orElse(null);
        Location home = locations.findByLocationId("DC1-BK-02-01-A-01").orElse(null);
        if (rules.count() > 0 || item == null || home == null || locations.findByLocationId("DC1-RS-01-01-A-01").isEmpty()) {
            return;
        }
        service.create(new PutawayRuleRequest(10, item.getItemId() + " to its own location", ActiveStatus.ACTIVE, item.getId(),
                null, null, PutawayStrategy.FIXED, home.getId(), null, PutawayHandling.EMPTY_LPN, null));
        service.create(new PutawayRuleRequest(20, "Pallets to reserve racking", ActiveStatus.ACTIVE, null, ContainerCategory.PALLET,
                null, PutawayStrategy.ZONE, null, zone("DC1-RS", "Reserve racking", "RS"), PutawayHandling.KEEP_LPN, null));
        service.create(new PutawayRuleRequest(30, "Everything else to shelving", ActiveStatus.ACTIVE, null, null, null,
                PutawayStrategy.ZONE, null, zone("DC1-BK", "Shelving", "BK"), PutawayHandling.EMPTY_LPN, null));
        service.create(new PutawayRuleRequest(40, "Any empty location", ActiveStatus.ACTIVE, null, null, null,
                PutawayStrategy.NEAREST_EMPTY, null, null, PutawayHandling.KEEP_LPN, null));
        log.info("Loaded 4 demo put-away rules");
    }

    /** A put-away zone of everything in a zone of the address. */
    private UUID zone(String code, String name, String addressZone) {
        UUID id = zones.create(new ZoneService.ZoneRequest(ZoneFunction.PUTAWAY, code, name, ActiveStatus.ACTIVE, null, null, null)).id();
        zones.add(id, new ZoneService.AddRequest(new ZoneService.Filter(null, "DC1", addressZone, null, null, null, null, null), false));
        return id;
    }
}
