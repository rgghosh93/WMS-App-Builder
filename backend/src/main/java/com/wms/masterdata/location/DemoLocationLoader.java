package com.wms.masterdata.location;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.wms.common.measure.DimensionUom;
import com.wms.common.measure.VolumeUom;
import com.wms.common.measure.WeightUom;

/**
 * Seeds a small demo warehouse (two zones of storage locations) when the
 * location table is empty. Enabled for local dev with {@code wms.demo-data.enabled}.
 * <ul>
 *   <li>RS = pallet reserve: 48 x 40 x 60 in, one pallet of one SKU, 2,500 lb</li>
 *   <li>BK = shelving: 24 x 24 x 18 in, up to 4 SKUs and 500 units, 250 lb</li>
 *   <li>PACK = four pack stations, DOCK = three dock doors, RCV = two receiving areas</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "wms.demo-data.enabled", havingValue = "true")
@Order(10)
public class DemoLocationLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoLocationLoader.class);
    private static final String AREA = "DC1";

    private final LocationRepository repository;

    public DemoLocationLoader(LocationRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (repository.count() > 0) {
            backfillCapacity();
            addStations(LocationType.PACKING, "PACK", 4, "72.00", "36.00", "36.00");
            addStations(LocationType.DOCK, "DOCK", 3, "108.00", "120.00", "120.00");
            addStations(LocationType.RECEIVING, "RCV", 2, "240.00", "240.00", "120.00");
            return;
        }
        List<Location> locations = new ArrayList<>();
        for (String zone : List.of("RS", "BK")) {
            boolean reserve = zone.equals("RS");
            for (int aisle = 1; aisle <= 3; aisle++) {
                for (int bay = 1; bay <= 4; bay++) {
                    for (String level : List.of("A", "B", "C")) {
                        for (int position = 1; position <= 2; position++) {
                            Location l = new Location(UUID.randomUUID());
                            l.setType(LocationType.STORAGE);
                            l.setArea(AREA);
                            l.setZone(zone);
                            l.setAisle("%02d".formatted(aisle));
                            l.setBay("%02d".formatted(bay));
                            l.setLevel(level);
                            l.setPosition("%02d".formatted(position));
                            l.setLocationId(String.join("-", l.getArea(), l.getZone(), l.getAisle(), l.getBay(), l.getLevel(), l.getPosition()));
                            l.setWidth(new BigDecimal(reserve ? "48.00" : "24.00"));
                            l.setDepth(new BigDecimal(reserve ? "40.00" : "24.00"));
                            l.setHeight(new BigDecimal(reserve ? "60.00" : "18.00"));
                            l.setDimensionUom(DimensionUom.IN);
                            applyDemoCapacity(l);
                            locations.add(l);
                        }
                    }
                }
            }
        }
        repository.saveAll(locations);
        log.info("Loaded {} demo storage locations", locations.size());
        addStations(LocationType.PACKING, "PACK", 4, "72.00", "36.00", "36.00");
        addStations(LocationType.DOCK, "DOCK", 3, "108.00", "120.00", "120.00");
        addStations(LocationType.RECEIVING, "RCV", 2, "240.00", "240.00", "120.00");
    }

    /** Pack stations or dock doors, added to the demo warehouse if it has none yet. */
    private void addStations(LocationType type, String zone, int count, String width, String depth, String height) {
        boolean demoWarehouse = repository.findByLocationId(AREA + "-RS-01-01-A-01").isPresent();
        if (!demoWarehouse || repository.existsByType(type)) {
            return;
        }
        for (int n = 1; n <= count; n++) {
            Location l = new Location(UUID.randomUUID());
            l.setType(type);
            l.setArea(AREA);
            l.setZone(zone);
            l.setPosition("%02d".formatted(n));
            l.setLocationId(String.join("-", l.getArea(), l.getZone(), l.getPosition()));
            l.setWidth(new BigDecimal(width));
            l.setDepth(new BigDecimal(depth));
            l.setHeight(new BigDecimal(height));
            l.setDimensionUom(DimensionUom.IN);
            repository.save(l);
        }
        log.info("Loaded {} demo {} locations", count, type.label().toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * Databases seeded before capacity existed: give the demo locations their
     * capacity, but only where none has been set yet.
     */
    private void backfillCapacity() {
        int updated = 0;
        for (Location l : repository.findAll()) {
            boolean demo = AREA.equals(l.getArea()) && ("RS".equals(l.getZone()) || "BK".equals(l.getZone()));
            boolean noCapacity = l.getMaxWeight() == null && l.getMaxVolume() == null
                    && l.getMaxLpns() == null && l.getMaxSkus() == null && l.getMaxUnits() == null;
            if (demo && noCapacity) {
                applyDemoCapacity(l);
                updated++;
            }
        }
        if (updated > 0) {
            log.info("Added demo capacity to {} existing demo locations", updated);
        }
    }

    private static void applyDemoCapacity(Location l) {
        if ("RS".equals(l.getZone())) {
            l.setMaxWeight(new BigDecimal("2500"));
            l.setMaxWeightUom(WeightUom.LB);
            l.setMaxVolume(new BigDecimal("66.667"));
            l.setMaxVolumeUom(VolumeUom.CU_FT);
            l.setMaxLpns(1);
            l.setMaxSkus(1);
        } else {
            l.setMaxWeight(new BigDecimal("250"));
            l.setMaxWeightUom(WeightUom.LB);
            l.setMaxVolume(new BigDecimal("6"));
            l.setMaxVolumeUom(VolumeUom.CU_FT);
            l.setMaxSkus(4);
            l.setMaxUnits(500);
        }
    }
}
