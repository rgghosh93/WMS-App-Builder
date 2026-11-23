package com.wms.masterdata.item;

import com.wms.common.ActiveStatus;
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

import com.wms.common.api.ApiException;
import com.wms.common.api.PageResponse;
import com.wms.users.Permission;
import com.wms.users.Requires;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/items")
public class ItemController {

    static final int MAX_PAGE_SIZE = 200;
    private static final Set<String> SORTABLE = Set.of("itemId", "description", "status", "barcode", "updatedAt");

    private final ItemService service;

    public ItemController(ItemService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<ItemResponse> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) ActiveStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "itemId") String sort,
            @RequestParam(defaultValue = "asc") String dir) {
        if (!SORTABLE.contains(sort)) {
            throw ApiException.invalid("Cannot sort by '" + sort + "'", Map.of("sort", "must be one of " + SORTABLE));
        }
        Sort.Direction direction = "desc".equalsIgnoreCase(dir) ? Sort.Direction.DESC : Sort.Direction.ASC;
        Sort order = Sort.by(direction, sort);
        if (!sort.equals("itemId")) {
            order = order.and(Sort.by("itemId"));
        }
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE), order);
        return PageResponse.of(service.search(new ItemSearch(q, status), pageable), i -> i);
    }

    @GetMapping("/{id}")
    public ItemResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping
    @Requires(Permission.MASTER_DATA)
    public ResponseEntity<ItemResponse> create(@Valid @RequestBody ItemRequest request) {
        ItemResponse created = service.create(request);
        return ResponseEntity.created(URI.create("/api/items/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    @Requires(Permission.MASTER_DATA)
    public ItemResponse update(@PathVariable UUID id, @Valid @RequestBody ItemRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    @Requires(Permission.MASTER_DATA)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }
}
