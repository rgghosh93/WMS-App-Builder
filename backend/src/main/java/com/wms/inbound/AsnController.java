package com.wms.inbound;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.wms.common.api.ApiException;
import com.wms.common.api.PageResponse;
import com.wms.inbound.InboundViews.AsnDetail;
import com.wms.inbound.InboundViews.AsnSummary;
import com.wms.users.Permission;
import com.wms.users.Requires;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/asns")
public class AsnController {

    private static final int MAX_PAGE_SIZE = 200;
    private static final Map<String, String> SORT = Map.of(
            "createdAt", "createdAt",
            "asnNumber", "asnNumber",
            "vendorName", "vendorName",
            "expectedDate", "expectedDate",
            "status", "status");

    private final AsnService service;

    public AsnController(AsnService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<AsnSummary> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) AsnStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "createdAt") String sort,
            @RequestParam(defaultValue = "desc") String dir) {
        String path = SORT.get(sort);
        if (path == null) {
            throw ApiException.invalid("Cannot sort by '" + sort + "'", Map.of("sort", "must be one of " + SORT.keySet()));
        }
        Sort.Direction direction = "asc".equalsIgnoreCase(dir) ? Sort.Direction.ASC : Sort.Direction.DESC;
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE),
                Sort.by(direction, path).and(Sort.by(Sort.Direction.DESC, "asnNumber")));
        return PageResponse.of(service.search(new AsnSearch(q, status), pageable), a -> a);
    }

    @GetMapping("/{id}")
    public AsnDetail get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping
    @Requires(Permission.INBOUND)
    public ResponseEntity<AsnDetail> create(@Valid @RequestBody AsnRequest request) {
        AsnDetail created = service.create(request);
        return ResponseEntity.created(URI.create("/api/asns/" + created.id())).body(created);
    }

    @PostMapping("/{id}/cancel")
    @Requires(Permission.INBOUND)
    public AsnDetail cancel(@PathVariable UUID id) {
        return service.cancel(id);
    }
}
