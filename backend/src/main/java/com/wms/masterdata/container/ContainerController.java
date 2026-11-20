package com.wms.masterdata.container;

import java.net.URI;
import java.util.Map;
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

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/containers")
public class ContainerController {

    static final int MAX_PAGE_SIZE = 200;

    /** API sort names mapped to entity paths. */
    private static final Map<String, String> SORTABLE = Map.of(
            "lpnId", "lpnId",
            "containerType", "containerType.containerTypeId",
            "status", "status",
            "location", "currentLocation.locationId",
            "updatedAt", "updatedAt");

    private final ContainerService service;

    public ContainerController(ContainerService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<ContainerResponse> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) UUID containerTypeId,
            @RequestParam(required = false) ContainerStatus status,
            @RequestParam(required = false) UUID locationId,
            @RequestParam(required = false) Boolean permanent,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "lpnId") String sort,
            @RequestParam(defaultValue = "asc") String dir) {
        String path = SORTABLE.get(sort);
        if (path == null) {
            throw ApiException.invalid("Cannot sort by '" + sort + "'", Map.of("sort", "must be one of " + SORTABLE.keySet()));
        }
        Sort.Direction direction = "desc".equalsIgnoreCase(dir) ? Sort.Direction.DESC : Sort.Direction.ASC;
        Sort order = Sort.by(direction, path);
        if (!sort.equals("lpnId")) {
            order = order.and(Sort.by("lpnId"));
        }
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE), order);
        return PageResponse.of(service.search(new ContainerSearch(q, containerTypeId, status, locationId, permanent), pageable), c -> c);
    }

    @GetMapping("/{id}")
    public ContainerResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping
    public ResponseEntity<ContainerResponse> create(@Valid @RequestBody ContainerRequest request) {
        ContainerResponse created = service.create(request);
        return ResponseEntity.created(URI.create("/api/containers/" + created.id())).body(created);
    }

    /** Creates a numbered run of containers, e.g. TOTE00001 to TOTE00500. */
    @PostMapping("/range")
    @ResponseStatus(HttpStatus.CREATED)
    public ContainerService.RangeResult createRange(@Valid @RequestBody ContainerRangeRequest request) {
        return service.createRange(request);
    }

    @PutMapping("/{id}")
    public ContainerResponse update(@PathVariable UUID id, @Valid @RequestBody ContainerRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }
}
