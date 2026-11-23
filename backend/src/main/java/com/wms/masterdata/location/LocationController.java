package com.wms.masterdata.location;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.wms.common.api.ApiException;
import com.wms.common.api.PageResponse;
import com.wms.users.Permission;
import com.wms.users.Requires;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/locations")
public class LocationController {

    static final int MAX_PAGE_SIZE = 200;
    private static final Set<String> SORTABLE = Set.of(
            "locationId", "type", "area", "zone", "aisle", "bay", "level", "position", "updatedAt");

    private final LocationService service;

    public LocationController(LocationService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<LocationResponse> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) LocationType type,
            @RequestParam(required = false) String area,
            @RequestParam(required = false) String zone,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "locationId") String sort,
            @RequestParam(defaultValue = "asc") String dir) {
        if (!SORTABLE.contains(sort)) {
            throw ApiException.invalid("Cannot sort by '" + sort + "'", Map.of("sort", "must be one of " + SORTABLE));
        }
        Sort.Direction direction = "desc".equalsIgnoreCase(dir) ? Sort.Direction.DESC : Sort.Direction.ASC;
        Sort order = Sort.by(direction, sort);
        if (!sort.equals("locationId")) {
            order = order.and(Sort.by("locationId"));
        }
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE), order);
        return PageResponse.of(service.search(new LocationSearch(q, type, area, zone), pageable), LocationResponse::from);
    }

    @GetMapping("/{id}")
    public LocationResponse get(@PathVariable UUID id) {
        return LocationResponse.from(service.get(id));
    }

    @PostMapping
    @Requires(Permission.MASTER_DATA)
    public ResponseEntity<LocationResponse> create(@Valid @RequestBody LocationRequest request) {
        LocationResponse created = LocationResponse.from(service.create(request));
        return ResponseEntity.created(URI.create("/api/locations/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    @Requires(Permission.MASTER_DATA)
    public LocationResponse update(@PathVariable UUID id, @Valid @RequestBody LocationRequest request) {
        return LocationResponse.from(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    @Requires(Permission.MASTER_DATA)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }

    /** Location types and the identifiers each one requires, so the UI can build its form. */
    @GetMapping("/types")
    public List<LocationTypeResponse> types() {
        return Arrays.stream(LocationType.values())
                .map(t -> new LocationTypeResponse(t, t.label(),
                        t.requiredIdentifiers().stream().map(LocationIdentifier::field).toList()))
                .toList();
    }

    public record LocationTypeResponse(LocationType type, String label, List<String> requiredIdentifiers) {
    }
}
