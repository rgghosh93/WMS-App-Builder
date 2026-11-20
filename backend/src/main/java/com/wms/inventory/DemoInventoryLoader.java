package com.wms.inventory;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.wms.masterdata.container.Container;
import com.wms.masterdata.container.ContainerRepository;
import com.wms.masterdata.container.ContainerStatus;
import com.wms.masterdata.container.ContainerType;
import com.wms.masterdata.container.ContainerTypeRepository;
import com.wms.masterdata.item.Item;
import com.wms.masterdata.item.ItemRepository;
import com.wms.masterdata.location.Location;
import com.wms.masterdata.location.LocationRepository;

/**
 * Seeds demo stock when there is none (local dev only), so the inventory view
 * has every shape to show:
 * <ul>
 *   <li>stock inside the permanent shelf bins (BIN00001...) in shelving aisle 01</li>
 *   <li>loose eaches straight on shelves in aisle 02, including serial-tracked items</li>
 *   <li>pallets in reserve: one of cartons, one of eaches, one of cartons of serial-tracked drills,
 *       a mixed pallet, and a pallet on quality hold</li>
 * </ul>
 * Runs after all master-data loaders.
 */
@Component
@ConditionalOnProperty(name = "wms.demo-data.enabled", havingValue = "true")
@Order(30)
public class DemoInventoryLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoInventoryLoader.class);

    private final InventoryRepository inventory;
    private final ItemRepository items;
    private final LocationRepository locations;
    private final ContainerRepository containers;
    private final ContainerTypeRepository containerTypes;

    private int records;
    private int pallets;
    private int cartons;

    public DemoInventoryLoader(InventoryRepository inventory, ItemRepository items, LocationRepository locations,
                               ContainerRepository containers, ContainerTypeRepository containerTypes) {
        this.inventory = inventory;
        this.items = items;
        this.locations = locations;
        this.containers = containers;
        this.containerTypes = containerTypes;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (inventory.count() > 0) {
            return;
        }
        ContainerType palletType = containerTypes.findByContainerTypeId("PLT-GMA").orElse(null);
        ContainerType cartonType = containerTypes.findByContainerTypeId("CTN-M").orElse(null);
        if (palletType == null || cartonType == null || items.findByItemId("SKU-10001").isEmpty()
                || locations.findByLocationId("DC1-RS-01-01-A-01").isEmpty()) {
            log.info("Demo master data not found; skipping demo inventory");
            return;
        }

        // Stock in permanent shelf bins (shelving aisle 01).
        inBin("BIN00001", "SKU-10003", 60, null, null, null, InventoryStatus.AVAILABLE, "2026-11-02");
        inBin("BIN00002", "SKU-40002", 35, null, null, null, InventoryStatus.AVAILABLE, "2026-11-03");
        inBin("BIN00003", "SKU-30001", 40, "VD2609A", null, "2027-09-30", InventoryStatus.AVAILABLE, "2026-10-12");
        inBin("BIN00003", "SKU-30001", 25, "VD2611B", null, "2027-11-30", InventoryStatus.AVAILABLE, "2026-11-09");
        inBin("BIN00004", "SKU-10004", 48, null, null, null, InventoryStatus.AVAILABLE, "2026-11-04");
        for (int n = 1; n <= 3; n++) {
            inBin("BIN00005", "SKU-40001", 1, null, "HP24A%05d".formatted(n), null, InventoryStatus.AVAILABLE, "2026-11-05");
        }
        inBin("BIN00006", "SKU-10002", 18, null, null, null, InventoryStatus.AVAILABLE, "2026-11-06");
        inBin("BIN00007", "SKU-30002", 12, "FA2602", null, "2029-02-28", InventoryStatus.AVAILABLE, "2026-10-20");
        inBin("BIN00008", "SKU-10004", 10, null, null, null, InventoryStatus.DAMAGED, "2026-11-04");

        // Loose eaches straight on shelves (shelving aisle 02, no bins).
        loose("DC1-BK-02-01-A-01", "SKU-20004", 30, "GR2610", null, "2027-08-15", InventoryStatus.AVAILABLE, "2026-11-10");
        loose("DC1-BK-02-01-A-01", "SKU-10004", 24, null, null, null, InventoryStatus.AVAILABLE, "2026-11-10");
        for (int n = 1; n <= 4; n++) {
            loose("DC1-BK-02-01-A-02", "SKU-40003", 1, null, "TH9K%05d".formatted(n), null, InventoryStatus.AVAILABLE, "2026-11-11");
        }
        loose("DC1-BK-02-02-A-01", "SKU-10005", 1, null, "LW5000123", null, InventoryStatus.AVAILABLE, "2026-11-12");
        loose("DC1-BK-02-02-A-01", "SKU-10005", 1, null, "LW5000124", null, InventoryStatus.AVAILABLE, "2026-11-12");

        // Reserve: a pallet of 4 cartons of protein bars, plus 3 loose eaches left on the pallet.
        Container p1 = pallet("DC1-RS-01-01-A-01", palletType);
        for (int n = 0; n < 4; n++) {
            Container carton = carton(p1, cartonType);
            in(carton, "SKU-20001", 6, "PB2610A", null, "2027-04-15", InventoryStatus.AVAILABLE, "2026-11-14");
        }
        in(p1, "SKU-20001", 3, "PB2610A", null, "2027-04-15", InventoryStatus.AVAILABLE, "2026-11-14");

        // Reserve: a pallet of sparkling water eaches, two lots.
        Container p2 = pallet("DC1-RS-01-01-A-02", palletType);
        in(p2, "SKU-20002", 96, "SW2611", null, "2027-05-01", InventoryStatus.AVAILABLE, "2026-11-15");
        in(p2, "SKU-20002", 24, "SW2610", null, "2027-04-01", InventoryStatus.AVAILABLE, "2026-11-15");

        // Reserve: a pallet with 2 cartons of serial-tracked drills (4 each).
        Container p3 = pallet("DC1-RS-01-01-B-01", palletType);
        int serial = 101;
        for (int c = 0; c < 2; c++) {
            Container carton = carton(p3, cartonType);
            for (int n = 0; n < 4; n++) {
                in(carton, "SKU-10001", 1, null, "DR18-%06d".formatted(serial++), null, InventoryStatus.AVAILABLE, "2026-11-16");
            }
        }

        // Reserve: a mixed pallet: two cartons of coffee and a carton of drill bit sets.
        Container p4 = pallet("DC1-RS-01-02-A-01", palletType);
        in(carton(p4, cartonType), "SKU-20003", 6, "CF2609", null, "2028-03-01", InventoryStatus.AVAILABLE, "2026-11-17");
        in(carton(p4, cartonType), "SKU-20003", 6, "CF2609", null, "2028-03-01", InventoryStatus.AVAILABLE, "2026-11-17");
        in(carton(p4, cartonType), "SKU-10002", 12, null, null, null, InventoryStatus.AVAILABLE, "2026-11-17");

        // Reserve: a pallet of granola on quality hold.
        Container p5 = pallet("DC1-RS-01-02-A-02", palletType);
        in(p5, "SKU-20004", 96, "GR2608", null, "2027-01-10", InventoryStatus.ON_HOLD, "2026-11-01");

        log.info("Loaded {} demo inventory records, {} pallets and {} cartons", records, pallets, cartons);
    }

    private Container pallet(String locationId, ContainerType type) {
        Container c = newLpn("P%08d".formatted(++pallets), type);
        c.setCurrentLocation(location(locationId));
        return containers.save(c);
    }

    private Container carton(Container pallet, ContainerType type) {
        Container c = newLpn("C%08d".formatted(++cartons), type);
        c.setParent(pallet);
        return containers.save(c);
    }

    private static Container newLpn(String lpnId, ContainerType type) {
        Container c = new Container(UUID.randomUUID());
        c.setLpnId(lpnId);
        c.setContainerType(type);
        c.setStatus(ContainerStatus.IN_USE);
        c.setPermanent(false);
        return c;
    }

    private void inBin(String binLpn, String itemId, int qty, String lot, String serial, String expiry,
                       InventoryStatus status, String received) {
        Container bin = containers.findByLpnId(binLpn).orElse(null);
        if (bin != null) {
            in(bin, itemId, qty, lot, serial, expiry, status, received);
        }
    }

    private void in(Container container, String itemId, int qty, String lot, String serial, String expiry,
                    InventoryStatus status, String received) {
        InventoryRecord r = record(itemId, qty, lot, serial, expiry, status, received);
        r.placeIn(container);
        inventory.save(r);
    }

    private void loose(String locationId, String itemId, int qty, String lot, String serial, String expiry,
                       InventoryStatus status, String received) {
        InventoryRecord r = record(itemId, qty, lot, serial, expiry, status, received);
        r.placeIn(location(locationId));
        inventory.save(r);
    }

    private InventoryRecord record(String itemId, int qty, String lot, String serial, String expiry,
                                   InventoryStatus status, String received) {
        Item item = items.findByItemId(itemId).orElseThrow();
        InventoryRecord r = new InventoryRecord(UUID.randomUUID(), item, qty);
        r.setLotNumber(lot);
        r.setSerialNumber(serial);
        r.setExpiryDate(expiry == null ? null : LocalDate.parse(expiry));
        r.setStatus(status);
        r.setReceivedAt(Instant.parse(received + "T14:00:00Z"));
        records++;
        return r;
    }

    private Location location(String locationId) {
        return locations.findByLocationId(locationId).orElseThrow();
    }
}
