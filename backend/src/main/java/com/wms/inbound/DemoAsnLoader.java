package com.wms.inbound;

import java.time.LocalDate;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.wms.masterdata.item.ItemRepository;

/** Two demo ASNs for local dev when there are none: one by item, one by announced LPN. */
@Component
@ConditionalOnProperty(name = "wms.demo-data.enabled", havingValue = "true")
@Order(50)
public class DemoAsnLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoAsnLoader.class);

    private final AsnRepository asns;
    private final AsnService service;
    private final ItemRepository items;

    public DemoAsnLoader(AsnRepository asns, AsnService service, ItemRepository items) {
        this.asns = asns;
        this.service = service;
        this.items = items;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (asns.count() > 0 || items.findByItemId("SKU-10001").isEmpty() || items.findByItemId("SKU-20001").isEmpty()) {
            return;
        }
        LocalDate today = LocalDate.now();
        service.create(new AsnRequest(null, "Acme Tool Supply", "PO-4501", today.plusDays(1), List.of(
                new AsnRequest.Line("SKU-10003", 144, null, null, null),
                new AsnRequest.Line("SKU-10004", 40, null, null, null),
                new AsnRequest.Line("SKU-10001", 2, null, null, null))));
        service.create(new AsnRequest(null, "Sunrise Foods", "PO-4502", today, List.of(
                new AsnRequest.Line("SKU-20001", 60, "PB2701A", today.plusDays(200), "SUN-PLT-001"),
                new AsnRequest.Line("SKU-20001", 60, "PB2701B", today.plusDays(230), "SUN-PLT-002"))));
        log.info("Loaded 2 demo ASNs");
    }
}
