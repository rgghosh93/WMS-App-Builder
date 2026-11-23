package com.wms.masterdata.container;

import java.net.URI;
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

import com.wms.common.ActiveStatus;
import com.wms.common.api.ApiException;
import com.wms.common.api.PageResponse;
import com.wms.facility.Site;
import com.wms.users.Permission;
import com.wms.users.Requires;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/container-types")
public class ContainerTypeController {

    static final int MAX_PAGE_SIZE = 200;
    private static final Set<String> SORTABLE = Set.of("containerTypeId", "description", "category", "status", "updatedAt");

    private final ContainerTypeService service;

    public ContainerTypeController(ContainerTypeService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<ContainerTypeResponse> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) ContainerCategory category,
            @RequestParam(required = false) ActiveStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "containerTypeId") String sort,
            @RequestParam(defaultValue = "asc") String dir) {
        if (!SORTABLE.contains(sort)) {
            throw ApiException.invalid("Cannot sort by '" + sort + "'", Map.of("sort", "must be one of " + SORTABLE));
        }
        Sort.Direction direction = "desc".equalsIgnoreCase(dir) ? Sort.Direction.DESC : Sort.Direction.ASC;
        Sort order = Sort.by(direction, sort);
        if (!sort.equals("containerTypeId")) {
            order = order.and(Sort.by("containerTypeId"));
        }
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE), order);
        return PageResponse.of(service.search(new ContainerTypeSearch(q, category, status), pageable),
                ContainerTypeResponse::from);
    }

    @GetMapping("/{id}")
    public ContainerTypeResponse get(@PathVariable UUID id) {
        return ContainerTypeResponse.from(service.get(id));
    }

    @PostMapping
    @Requires(Permission.MASTER_DATA)
    public ResponseEntity<ContainerTypeResponse> create(@Valid @RequestBody ContainerTypeRequest request) {
        ContainerTypeResponse created = ContainerTypeResponse.from(service.create(request));
        return ResponseEntity.created(URI.create("/api/container-types/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    @Requires(Permission.MASTER_DATA)
    public ContainerTypeResponse update(@PathVariable UUID id, @Valid @RequestBody ContainerTypeRequest request) {
        return ContainerTypeResponse.from(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    @Requires(Permission.MASTER_DATA)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        // It may be in use in any facility.
        Site.everywhere(() -> {
            service.delete(id);
            return null;
        });
    }
}
