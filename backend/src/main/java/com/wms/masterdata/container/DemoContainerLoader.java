package com.wms.masterdata.container;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.wms.masterdata.location.Location;
import com.wms.masterdata.location.LocationRepository;

/**
 * Seeds demo permanent containers when the table is empty (local dev only):
 * 30 standard totes waiting empty, and 24 shelf bins sitting in shelving (BK) locations.
 * Runs after the container type and location loaders.
 */
@Component
@ConditionalOnProperty(name = "wms.demo-data.enabled", havingValue = "true")
@Order(20)
public class DemoContainerLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoContainerLoader.class);

    private final ContainerRepository repository;
    private final ContainerTypeRepository typeRepository;
    private final LocationRepository locationRepository;

    public DemoContainerLoader(ContainerRepository repository, ContainerTypeRepository typeRepository,
                               LocationRepository locationRepository) {
        this.repository = repository;
        this.typeRepository = typeRepository;
        this.locationRepository = locationRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (repository.count() > 0) {
            return;
        }
        ContainerType tote = typeRepository.findByContainerTypeId("TOTE-STD").orElse(null);
        ContainerType bin = typeRepository.findByContainerTypeId("BIN-SHELF").orElse(null);
        if (tote == null || bin == null) {
            log.info("Demo container types not found; skipping demo containers");
            return;
        }

        List<Container> containers = new ArrayList<>();
        for (int n = 1; n <= 30; n++) {
            ContainerStatus status = n == 29 ? ContainerStatus.ON_HOLD : n == 30 ? ContainerStatus.RETIRED : ContainerStatus.AVAILABLE;
            containers.add(container("TOTE%05d".formatted(n), tote, status, null,
                    n == 29 ? "Cracked handle, waiting for repair" : null));
        }

        List<Location> shelving = locationRepository.findAll(
                (root, query, cb) -> cb.equal(root.get("zone"), "BK"),
                PageRequest.of(0, 24, Sort.by("locationId"))).getContent();
        for (int n = 0; n < shelving.size(); n++) {
            containers.add(container("BIN%05d".formatted(n + 1), bin, ContainerStatus.IN_USE, shelving.get(n), null));
        }

        repository.saveAll(containers);
        log.info("Loaded {} demo containers", containers.size());
    }

    private static Container container(String lpnId, ContainerType type, ContainerStatus status, Location location, String notes) {
        Container c = new Container(UUID.randomUUID());
        c.setLpnId(lpnId);
        c.setContainerType(type);
        c.setStatus(status);
        c.setCurrentLocation(location);
        c.setNotes(notes);
        return c;
    }
}
