package com.wms.masterdata.item;

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
import com.wms.masterdata.uom.UnitOfMeasure;
import com.wms.masterdata.uom.UnitOfMeasureRepository;

/**
 * Gives the demo items case and pallet packaging when no item has any packaging
 * yet (local dev only). Runs after the item and unit-of-measure loaders.
 */
@Component
@ConditionalOnProperty(name = "wms.demo-data.enabled", havingValue = "true")
@Order(20)
public class DemoItemPackLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoItemPackLoader.class);

    private final ItemRepository itemRepository;
    private final ItemUomRepository itemUomRepository;
    private final UnitOfMeasureRepository uomRepository;

    public DemoItemPackLoader(ItemRepository itemRepository, ItemUomRepository itemUomRepository,
                              UnitOfMeasureRepository uomRepository) {
        this.itemRepository = itemRepository;
        this.itemUomRepository = itemUomRepository;
        this.uomRepository = uomRepository;
    }

    /** Dimensions in inches, weight in pounds; null = not set. Case barcodes follow the GTIN-14 "1" + UPC pattern. */
    private record Pack(String itemId, String uom, int quantity, String barcode, String w, String d, String h, String weightLb) {
    }

    private static final List<Pack> PACKS = List.of(
            new Pack("SKU-10001", "CS", 4, "10012345100015", "26", "13", "11", "17.5"),
            new Pack("SKU-10001", "PL", 160, null, "48", "40", "50", "740"),
            new Pack("SKU-10002", "CS", 12, "10012345100022", "17", "9", "11", "13.8"),
            new Pack("SKU-10002", "PL", 600, null, null, null, null, null),
            new Pack("SKU-10003", "IP", 12, "20012345100036", "7", "6", "5", "1.9"),
            new Pack("SKU-10003", "CS", 144, "10012345100039", "15", "13", "11", "23"),
            new Pack("SKU-20001", "CS", 6, "10012345200012", "17", "13", "5", "10"),
            new Pack("SKU-20001", "PL", 480, null, "48", "40", "45", "830"),
            new Pack("SKU-20002", "CS", 2, "10012345200029", "16", "11", "10", "21.2"),
            new Pack("SKU-20002", "LY", 24, null, null, null, null, null),
            new Pack("SKU-20002", "PL", 120, null, "48", "40", "55", "1310"),
            new Pack("SKU-20003", "CS", 6, "10012345200036", "18", "10", "9", "13.1"),
            new Pack("SKU-20003", "PL", 300, null, null, null, null, null),
            new Pack("SKU-40002", "IP", 10, "20012345400023", "6", "5", "8", "2.1"),
            new Pack("SKU-40002", "CS", 100, "10012345400026", "16", "12", "17", "21.5"));

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (itemUomRepository.count() > 0) {
            return;
        }
        int added = 0;
        for (Pack p : PACKS) {
            Item item = itemRepository.findByItemId(p.itemId()).orElse(null);
            UnitOfMeasure uom = uomRepository.findByUomCode(p.uom()).orElse(null);
            if (item == null || uom == null) {
                continue;
            }
            ItemUom pack = new ItemUom(UUID.randomUUID(), item, uom);
            pack.setQuantity(p.quantity());
            pack.setBarcode(p.barcode());
            if (p.w() != null) {
                pack.setWidth(new BigDecimal(p.w()));
                pack.setDepth(new BigDecimal(p.d()));
                pack.setHeight(new BigDecimal(p.h()));
                pack.setDimensionUom(DimensionUom.IN);
            }
            if (p.weightLb() != null) {
                pack.setWeight(new BigDecimal(p.weightLb()));
                pack.setWeightUom(WeightUom.LB);
            }
            itemUomRepository.save(pack);
            added++;
        }
        if (added > 0) {
            log.info("Added {} demo packaging levels to demo items", added);
        }
    }
}
