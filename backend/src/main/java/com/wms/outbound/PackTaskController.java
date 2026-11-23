package com.wms.outbound;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.wms.common.api.PageResponse;
import com.wms.outbound.PackingViews.PackTaskView;
import com.wms.outbound.PackingViews.StationView;

/** Looking at packing work from a desk. */
@RestController
@RequestMapping("/api/pack-tasks")
public class PackTaskController {

    private final PackingService packing;

    public PackTaskController(PackingService packing) {
        this.packing = packing;
    }

    @GetMapping
    public PageResponse<PackTaskView> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) PackTaskStatus status,
            @RequestParam(required = false) UUID stationId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, OutboundController.MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "taskNumber"));
        return PageResponse.of(packing.search(new PackTaskSearch(q, status, stationId), pageable), t -> t);
    }

    @GetMapping("/stations")
    public List<StationView> stations() {
        return packing.stations();
    }

    @GetMapping("/{id}")
    public PackTaskView get(@PathVariable UUID id) {
        return packing.get(id);
    }
}
