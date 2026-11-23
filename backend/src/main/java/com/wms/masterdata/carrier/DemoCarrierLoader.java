package com.wms.masterdata.carrier;

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

/** Two made-up carriers for local dev when there are none. */
@Component
@ConditionalOnProperty(name = "wms.demo-data.enabled", havingValue = "true")
@Order(10)
public class DemoCarrierLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoCarrierLoader.class);

    private final CarrierRepository repository;

    public DemoCarrierLoader(CarrierRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (repository.count() > 0) {
            return;
        }
        Carrier parcel = carrier("DPARCEL", "Demo Parcel", CarrierMode.PARCEL, true);
        parcel.getServices().add(new CarrierService(UUID.randomUUID(), parcel, "GROUND", "Ground"));
        parcel.getServices().add(new CarrierService(UUID.randomUUID(), parcel, "2DAY", "2-Day"));
        Carrier freight = carrier("DFREIGHT", "Demo Freight", CarrierMode.LTL, false);
        freight.getServices().add(new CarrierService(UUID.randomUUID(), freight, "LTL", "Standard LTL"));
        repository.saveAll(List.of(parcel, freight));
        log.info("Loaded 2 demo carriers");
    }

    private static Carrier carrier(String code, String name, CarrierMode mode, boolean trackingRequired) {
        Carrier c = new Carrier(UUID.randomUUID());
        c.setCarrierCode(code);
        c.setName(name);
        c.setMode(mode);
        c.setTrackingRequired(trackingRequired);
        c.setStatus(ActiveStatus.ACTIVE);
        return c;
    }
}
