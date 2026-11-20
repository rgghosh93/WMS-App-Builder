package com.wms.outbound;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.wms.common.api.ApiException;
import com.wms.common.api.PageResponse;
import com.wms.inventory.InventoryStatus;
import com.wms.outbound.OutboundViews.AllocationView;
import com.wms.outbound.OutboundViews.OrderDetail;
import com.wms.outbound.OutboundViews.OrderSummary;
import com.wms.outbound.OutboundViews.TaskView;

import jakarta.validation.Valid;

@RestController
public class OutboundController {

    static final int MAX_PAGE_SIZE = 200;

    private static final Map<String, String> ORDER_SORT = Map.of(
            "createdAt", "createdAt",
            "orderNumber", "orderNumber",
            "customerName", "customerName",
            "requestedShipDate", "requestedShipDate",
            "priority", "priority",
            "status", "status");

    private final OrderService orders;

    public OutboundController(OrderService orders) {
        this.orders = orders;
    }

    @GetMapping("/api/orders")
    public PageResponse<OrderSummary> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "createdAt") String sort,
            @RequestParam(defaultValue = "desc") String dir) {
        String path = ORDER_SORT.get(sort);
        if (path == null) {
            throw ApiException.invalid("Cannot sort by '" + sort + "'", Map.of("sort", "must be one of " + ORDER_SORT.keySet()));
        }
        Sort.Direction direction = "asc".equalsIgnoreCase(dir) ? Sort.Direction.ASC : Sort.Direction.DESC;
        Sort order = Sort.by(direction, path).and(Sort.by(Sort.Direction.DESC, "orderNumber"));
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE), order);
        return PageResponse.of(orders.search(new OrderSearch(q, status), pageable), o -> o);
    }

    @GetMapping("/api/orders/{id}")
    public OrderDetail get(@PathVariable UUID id) {
        return orders.get(id);
    }

    /**
     * Receives an order. It is allocated straight away, creating a pick task, unless
     * {@code allocate=false} (e.g. to hold it for a batch with other orders).
     */
    @PostMapping("/api/orders")
    public ResponseEntity<OrderDetail> create(@Valid @RequestBody OrderRequest request,
                                              @RequestParam(required = false) Boolean allocate) {
        OrderDetail created = allocate == null ? orders.create(request) : orders.create(request, allocate);
        return ResponseEntity.created(URI.create("/api/orders/" + created.id())).body(created);
    }

    @PostMapping("/api/orders/{id}/allocate")
    public OrderDetail allocate(@PathVariable UUID id) {
        return orders.allocate(id);
    }

    /** Allocates several orders onto one batch pick task. */
    @PostMapping("/api/orders/batch-allocate")
    public OrderService.BatchAllocation allocateBatch(@RequestBody BatchRequest request) {
        return orders.allocateBatch(request.orderIds());
    }

    public record BatchRequest(List<UUID> orderIds) {
    }

    @PostMapping("/api/orders/{id}/cancel")
    public OrderDetail cancel(@PathVariable UUID id) {
        return orders.cancel(id);
    }

    /**
     * Stock reserved for orders, newest first. Besides the page, returns the total
     * quantity reserved across all allocations.
     */
    @GetMapping("/api/allocations")
    public AllocationList allocations(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) OrderStatus orderStatus,
            @RequestParam(required = false) InventoryStatus inventoryStatus,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id")));
        OrderService.AllocationPage result = orders.searchAllocations(
                new AllocationSearch(q, orderStatus, inventoryStatus), pageable);
        Page<AllocationView> p = result.page();
        return new AllocationList(p.getContent(), p.getNumber(), p.getSize(), p.getTotalElements(), p.getTotalPages(),
                result.totalAllocatedQuantity());
    }

    /** A page of allocations, plus the total quantity reserved across every allocation. */
    public record AllocationList(
            List<AllocationView> content,
            int page,
            int size,
            long totalElements,
            int totalPages,
            long totalAllocatedQuantity) {
    }

    @GetMapping("/api/pick-tasks/{id}")
    public TaskView task(@PathVariable UUID id) {
        return orders.getTask(id);
    }

    @GetMapping("/api/pick-tasks")
    public PageResponse<TaskView> tasks(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) PickTaskStatus status,
            @RequestParam(required = false) PickMethod pickMethod,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "taskNumber"));
        return PageResponse.of(orders.searchTasks(new PickTaskSearch(q, status, pickMethod), pageable), t -> t);
    }
}
