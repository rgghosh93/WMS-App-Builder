package com.wms.masterdata.container;

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

import com.wms.common.ActiveStatus;
import com.wms.common.measure.DimensionUom;
import com.wms.common.measure.VolumeUom;
import com.wms.common.measure.WeightUom;

/** Seeds common container types when the table is empty (local dev only). */
@Component
@ConditionalOnProperty(name = "wms.demo-data.enabled", havingValue = "true")
@Order(10)
public class DemoContainerTypeLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoContainerTypeLoader.class);

    private final ContainerTypeRepository repository;

    public DemoContainerTypeLoader(ContainerTypeRepository repository) {
        this.repository = repository;
    }

    /** Dimensions in inches, weights in pounds, volume in cubic feet; null = not set. */
    private record Demo(String id, String description, ContainerCategory category, boolean reusable,
                        String w, String d, String h, String tare, String maxWeight, String maxVolume) {
    }

    private static final List<Demo> DEMO = List.of(
            new Demo("PLT-GMA", "GMA pallet, 48 x 40, wood", ContainerCategory.PALLET, true, "48", "40", "60", "40", "2500", "66.667"),
            new Demo("PLT-EUR", "Euro pallet, 1200 x 800 mm", ContainerCategory.PALLET, true, "47.24", "31.5", "60", "55", "3300", null),
            new Demo("CTN-S", "Small shipping carton", ContainerCategory.CARTON, false, "12", "9", "6", "0.4", "30", "0.375"),
            new Demo("CTN-M", "Medium shipping carton", ContainerCategory.CARTON, false, "18", "14", "12", "0.9", "50", "1.75"),
            new Demo("CTN-L", "Large shipping carton", ContainerCategory.CARTON, false, "24", "18", "18", "1.6", "65", "4.5"),
            new Demo("TOTE-STD", "Standard picking tote", ContainerCategory.TOTE, true, "24", "16", "12", "3.5", "70", "2.667"),
            new Demo("TOTE-SM", "Small picking tote", ContainerCategory.TOTE, true, "16", "12", "8", "1.8", "40", null),
            new Demo("BIN-SHELF", "Shelf bin, open front", ContainerCategory.BIN, true, "11", "18", "7", "1.1", "25", null),
            new Demo("CAGE-ROLL", "Roll cage", ContainerCategory.CAGE, true, "32", "28", "66", "85", "1100", null));

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (repository.count() > 0) {
            return;
        }
        List<ContainerType> types = DEMO.stream().map(DemoContainerTypeLoader::toEntity).toList();
        repository.saveAll(types);
        log.info("Loaded {} demo container types", types.size());
    }

    private static ContainerType toEntity(Demo d) {
        ContainerType t = new ContainerType(UUID.randomUUID());
        t.setContainerTypeId(d.id());
        t.setDescription(d.description());
        t.setCategory(d.category());
        t.setStatus(ActiveStatus.ACTIVE);
        t.setReusable(d.reusable());
        t.setWidth(new BigDecimal(d.w()));
        t.setDepth(new BigDecimal(d.d()));
        t.setHeight(new BigDecimal(d.h()));
        t.setDimensionUom(DimensionUom.IN);
        if (d.tare() != null) {
            t.setTareWeight(new BigDecimal(d.tare()));
            t.setTareWeightUom(WeightUom.LB);
        }
        if (d.maxWeight() != null) {
            t.setMaxWeight(new BigDecimal(d.maxWeight()));
            t.setMaxWeightUom(WeightUom.LB);
        }
        if (d.maxVolume() != null) {
            t.setMaxVolume(new BigDecimal(d.maxVolume()));
            t.setMaxVolumeUom(VolumeUom.CU_FT);
        }
        return t;
    }
}
