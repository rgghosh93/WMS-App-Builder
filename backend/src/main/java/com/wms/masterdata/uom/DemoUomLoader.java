package com.wms.masterdata.uom;

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

/** Seeds common packaging units when only the base unit exists (local dev only). */
@Component
@ConditionalOnProperty(name = "wms.demo-data.enabled", havingValue = "true")
@Order(10)
public class DemoUomLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoUomLoader.class);

    private final UnitOfMeasureRepository repository;

    public DemoUomLoader(UnitOfMeasureRepository repository) {
        this.repository = repository;
    }

    private record Demo(String code, String description, UomKind kind) {
    }

    private static final List<Demo> DEMO = List.of(
            new Demo("IP", "Inner pack", UomKind.INNER_PACK),
            new Demo("CS", "Case", UomKind.CASE),
            new Demo("LY", "Pallet layer", UomKind.LAYER),
            new Demo("PL", "Pallet", UomKind.PALLET));

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (repository.count() > 1) {
            return;
        }
        List<UnitOfMeasure> units = DEMO.stream().map(d -> {
            UnitOfMeasure u = new UnitOfMeasure(UUID.randomUUID());
            u.setUomCode(d.code());
            u.setDescription(d.description());
            u.setKind(d.kind());
            u.setStatus(ActiveStatus.ACTIVE);
            return u;
        }).toList();
        repository.saveAll(units);
        log.info("Loaded {} demo units of measure", units.size());
    }
}
