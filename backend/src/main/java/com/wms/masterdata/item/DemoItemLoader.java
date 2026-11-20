package com.wms.masterdata.item;

import com.wms.common.ActiveStatus;
import java.math.BigDecimal;
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
import com.wms.common.measure.WeightUom;

/** Seeds demo items when the item table is empty (local dev only). */
@Component
@ConditionalOnProperty(name = "wms.demo-data.enabled", havingValue = "true")
@Order(10)
public class DemoItemLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoItemLoader.class);

    private final ItemRepository repository;

    public DemoItemLoader(ItemRepository repository) {
        this.repository = repository;
    }

    private record Demo(String id, String description, String barcode, boolean lot, boolean serial, Integer shelfLife,
                        String w, String d, String h, String weightLb) {
    }

    private static final List<Demo> DEMO = List.of(
            new Demo("SKU-10001", "Cordless drill 18V", "0012345100018", false, true, null, "12", "4", "10", "4.2"),
            new Demo("SKU-10002", "Drill bit set, 29 piece", "0012345100025", false, false, null, "8", "2", "5", "1.1"),
            new Demo("SKU-10003", "Safety glasses, clear", "0012345100032", false, false, null, "6", "2.5", "2", "0.15"),
            new Demo("SKU-10004", "Work gloves, large", "0012345100049", false, false, null, "10", "5", "1", "0.3"),
            new Demo("SKU-10005", "LED work light", "0012345100056", false, true, null, "9", "4", "7", "2.0"),
            new Demo("SKU-20001", "Protein bar, chocolate, 12 pack", "0012345200015", true, false, 270, "8", "6", "2", "1.6"),
            new Demo("SKU-20002", "Sparkling water, lime, 12 pack", "0012345200022", true, false, 365, "16", "11", "5", "10.5"),
            new Demo("SKU-20003", "Ground coffee, 2 lb bag", "0012345200039", true, false, 540, "9", "5", "4", "2.1"),
            new Demo("SKU-20004", "Granola, honey almond", "0012345200046", true, false, 300, "8", "3", "11", "1.2"),
            new Demo("SKU-30001", "Vitamin D3 gummies, 90 ct", "0012345300012", true, false, 730, "3", "3", "5", "0.4"),
            new Demo("SKU-30002", "First aid kit, 100 piece", "0012345300029", true, false, 1095, "9", "3", "7", "1.0"),
            new Demo("SKU-40001", "Bluetooth headphones", "0012345400019", false, true, null, "8", "3", "8", "0.9"),
            new Demo("SKU-40002", "USB-C charging cable, 6 ft", "0012345400026", false, false, null, "5", "1", "7", "0.2"),
            new Demo("SKU-40003", "Smart thermostat", "0012345400033", false, true, null, "6", "3", "6", "0.8"),
            new Demo("SKU-50001", "Storage tote, 27 gal", null, false, false, null, null, null, null, null));

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (repository.count() > 0) {
            return;
        }
        List<Item> items = DEMO.stream().map(DemoItemLoader::toItem).toList();
        items.getLast().setStatus(ActiveStatus.INACTIVE);
        repository.saveAll(items);
        log.info("Loaded {} demo items", items.size());
    }

    private static Item toItem(Demo d) {
        Item item = new Item(UUID.randomUUID());
        item.setItemId(d.id());
        item.setDescription(d.description());
        item.setStatus(ActiveStatus.ACTIVE);
        item.setBarcode(d.barcode());
        item.setLotControlled(d.lot());
        item.setSerialControlled(d.serial());
        item.setExpiryControlled(d.shelfLife() != null);
        item.setShelfLifeDays(d.shelfLife());
        if (d.w() != null) {
            item.setWidth(new BigDecimal(d.w()));
            item.setDepth(new BigDecimal(d.d()));
            item.setHeight(new BigDecimal(d.h()));
            item.setDimensionUom(DimensionUom.IN);
        }
        if (d.weightLb() != null) {
            item.setWeight(new BigDecimal(d.weightLb()));
            item.setWeightUom(WeightUom.LB);
        }
        return item;
    }
}
