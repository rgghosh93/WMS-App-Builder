package com.wms.inventory;

import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.wms.common.api.PageResponse;
import com.wms.inventory.InventoryViews.ContainerDetail;
import com.wms.inventory.InventoryViews.ContainerSummary;
import com.wms.inventory.InventoryViews.LocationDetail;
import com.wms.inventory.InventoryViews.LocationSummary;
import com.wms.masterdata.container.ContainerCategory;

@RestController
@RequestMapping("/api/inventory")
public class InventoryController {

    static final int MAX_PAGE_SIZE = 200;

    private final InventoryQueryService service;

    public InventoryController(InventoryQueryService service) {
        this.service = service;
    }

    /** Locations with totals of what they hold, including inside containers. */
    @GetMapping("/locations")
    public PageResponse<LocationSummary> locations(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String zone,
            @RequestParam(defaultValue = "true") boolean occupied,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "asc") String dir) {
        Sort.Direction direction = "desc".equalsIgnoreCase(dir) ? Sort.Direction.DESC : Sort.Direction.ASC;
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE),
                Sort.by(direction, "locationId"));
        return PageResponse.of(service.locations(new InventoryLocationSearch(q, zone, occupied), pageable), s -> s);
    }

    /** Everything in one location: loose stock, and containers with what is inside them. */
    @GetMapping("/locations/{id}")
    public LocationDetail location(@PathVariable UUID id) {
        return service.location(id);
    }

    /** Containers (including ones inside other containers) with totals of what they hold. */
    @GetMapping("/containers")
    public PageResponse<ContainerSummary> containers(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) ContainerCategory category,
            @RequestParam(required = false) Boolean permanent,
            @RequestParam(defaultValue = "true") boolean withStock,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "asc") String dir) {
        Sort.Direction direction = "desc".equalsIgnoreCase(dir) ? Sort.Direction.DESC : Sort.Direction.ASC;
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE),
                Sort.by(direction, "lpnId"));
        return PageResponse.of(
                service.containers(new ContainerInventorySearch(q, category, permanent, withStock), pageable), s -> s);
    }

    /** One container with everything inside it, and where it is. */
    @GetMapping("/containers/{id}")
    public ContainerDetail container(@PathVariable UUID id) {
        return service.container(id);
    }
}
