package com.wms.outbound;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wms.common.ActiveStatus;
import com.wms.common.Codes;
import com.wms.common.api.ApiException;
import com.wms.masterdata.item.Item;
import com.wms.masterdata.item.ItemRepository;
import com.wms.outbound.OrderEvent.Type;
import com.wms.outbound.OutboundViews.AllocationView;
import com.wms.outbound.OutboundViews.EventView;
import com.wms.outbound.OutboundViews.LineView;
import com.wms.outbound.OutboundViews.OrderDetail;
import com.wms.outbound.OutboundViews.OrderSummary;
import com.wms.outbound.OutboundViews.TaskView;

@Service
@Transactional
public class OrderService {

    private final OrderRepository orders;
    private final AllocationRepository allocations;
    static final int MAX_BATCH = 50;

    private final PickTaskRepository tasks;
    private final PickTaskDetailRepository taskDetails;
    private final OrderEventRepository events;
    private final ItemRepository items;
    private final AllocationService allocationService;
    private final boolean autoAllocate;

    public OrderService(OrderRepository orders, AllocationRepository allocations, PickTaskRepository tasks,
                        PickTaskDetailRepository taskDetails, OrderEventRepository events, ItemRepository items,
                        AllocationService allocationService,
                        @Value("${wms.outbound.auto-allocate:true}") boolean autoAllocate) {
        this.orders = orders;
        this.allocations = allocations;
        this.tasks = tasks;
        this.taskDetails = taskDetails;
        this.events = events;
        this.items = items;
        this.allocationService = allocationService;
        this.autoAllocate = autoAllocate;
    }

    @Transactional(readOnly = true)
    public Page<OrderSummary> search(OrderSearch search, Pageable pageable) {
        Page<OutboundOrder> page = orders.findAll(search.toSpecification(), pageable);
        Map<UUID, Long> openTasks = new HashMap<>();
        if (page.hasContent()) {
            for (Object[] row : tasks.countOpenByOrder(page.getContent().stream().map(OutboundOrder::getId).toList())) {
                openTasks.put((UUID) row[0], ((Number) row[1]).longValue());
            }
        }
        return page.map(o -> {
            List<OrderLine> open = o.getLines().stream().filter(l -> l.getStatus() != LineStatus.CANCELLED).toList();
            return new OrderSummary(o.getId(), o.getOrderNumber(), o.getCustomerName(), o.getRequestedShipDate(),
                    o.getPriority(), o.getStatus(), o.getLines().size(),
                    open.stream().mapToLong(OrderLine::getOrderedQuantity).sum(),
                    open.stream().mapToLong(OrderLine::getAllocatedQuantity).sum(),
                    openTasks.getOrDefault(o.getId(), 0L), o.getCreatedAt());
        });
    }

    @Transactional(readOnly = true)
    public Page<TaskView> searchTasks(PickTaskSearch search, Pageable pageable) {
        return tasks.findAll(search.toSpecification(), pageable).map(TaskView::of);
    }

    @Transactional(readOnly = true)
    public TaskView getTask(UUID id) {
        return TaskView.of(tasks.findById(id).orElseThrow(() -> ApiException.notFound("Pick task not found")));
    }

    /** All stock currently reserved for orders, with each reservation's pick task. */
    @Transactional(readOnly = true)
    public AllocationPage searchAllocations(AllocationSearch search, Pageable pageable) {
        Page<Allocation> page = allocations.findAll(search.toSpecification(), pageable);
        List<AllocationView> views = allocationViews(page.getContent());
        Map<UUID, AllocationView> byId = new HashMap<>();
        views.forEach(v -> byId.put(v.id(), v));
        return new AllocationPage(page.map(a -> byId.get(a.getId())), allocations.totalQuantity());
    }

    /** A page of allocations plus the total reserved across all of them (not just this page). */
    public record AllocationPage(Page<AllocationView> page, long totalAllocatedQuantity) {
    }

    private List<AllocationView> allocationViews(List<Allocation> list) {
        Map<UUID, PickTaskDetail> taskByAllocation = new HashMap<>();
        if (!list.isEmpty()) {
            taskDetails.findByAllocation_IdIn(list.stream().map(Allocation::getId).toList())
                    .forEach(d -> taskByAllocation.put(d.getAllocation().getId(), d));
        }
        return list.stream().map(a -> AllocationView.of(a, taskByAllocation.get(a.getId()))).toList();
    }

    @Transactional(readOnly = true)
    public OrderDetail get(UUID id) {
        return detail(load(id));
    }

    /** Receives an order and, unless turned off, allocates it straight away. */
    public OrderDetail create(OrderRequest request) {
        return create(request, autoAllocate);
    }

    /** Receives an order, allocating it now or leaving it New (e.g. to batch it with others). */
    public OrderDetail create(OrderRequest request, boolean allocate) {
        Map<String, String> errors = new LinkedHashMap<>();
        String number = request.orderNumber() == null || request.orderNumber().isBlank()
                ? "SO%06d".formatted(orders.nextOrderNumber())
                : Codes.normalize(request.orderNumber());
        if (orders.findByOrderNumber(number).isPresent()) {
            throw ApiException.conflict("An order with this number already exists",
                    Map.of("orderNumber", "already used by another order"));
        }

        OutboundOrder order = new OutboundOrder(UUID.randomUUID(), number);
        order.setCustomerName(request.customerName().trim());
        order.setRequestedShipDate(request.requestedShipDate());
        order.setPriority(request.priority() == null ? 5 : request.priority());

        List<OrderRequest.Line> requested = request.lines();
        for (int i = 0; i < requested.size(); i++) {
            OrderRequest.Line line = requested.get(i);
            String prefix = "lines[" + i + "].";
            Item item = items.findByItemId(Codes.normalize(line.itemId())).orElse(null);
            String lot = line.requestedLot() == null || line.requestedLot().isBlank() ? null : line.requestedLot().trim();
            if (item == null) {
                errors.put(prefix + "itemId", "item " + line.itemId().trim() + " not found");
                continue;
            }
            if (item.getStatus() == ActiveStatus.INACTIVE) {
                errors.put(prefix + "itemId", item.getItemId() + " is inactive");
            }
            if (lot != null && !item.isLotControlled()) {
                errors.put(prefix + "requestedLot", item.getItemId() + " isn't lot-tracked");
            }
            order.getLines().add(new OrderLine(UUID.randomUUID(), order, i + 1, item, line.quantity(), lot));
        }
        if (!errors.isEmpty()) {
            throw ApiException.invalid("Some lines need attention", errors);
        }

        // save() hands back the managed copy; keep working with that one so later changes are stored.
        order = orders.save(order);
        long eaches = order.getLines().stream().mapToLong(OrderLine::getOrderedQuantity).sum();
        events.save(new OrderEvent(order, Type.RECEIVED, "Order received from %s: %d line%s, %d EA".formatted(
                order.getCustomerName(), order.getLines().size(), order.getLines().size() == 1 ? "" : "s", eaches)));
        if (allocate) {
            allocationService.allocate(order);
        }
        orders.flush();
        return detail(order);
    }

    /** Tries again to allocate what is still missing, e.g. after stock arrived. */
    public OrderDetail allocate(UUID id) {
        OutboundOrder order = load(id);
        if (order.getStatus() == OrderStatus.CANCELLED) {
            throw ApiException.conflict("Order " + order.getOrderNumber() + " is cancelled.", Map.of());
        }
        if (order.getStatus() == OrderStatus.ALLOCATED) {
            throw ApiException.conflict("Order " + order.getOrderNumber() + " is already fully allocated.", Map.of());
        }
        allocationService.allocate(order);
        orders.flush();
        return detail(order);
    }

    /**
     * Allocates several orders together onto one batch pick task, so one walk picks for all of them.
     * Orders must be distinct and still need stock (new, partially allocated or backordered).
     *
     * @return the batch task, or null if no stock could be reserved for any of them
     */
    public BatchAllocation allocateBatch(List<UUID> orderIds) {
        List<UUID> ids = orderIds == null ? List.of() : orderIds.stream().distinct().toList();
        if (ids.size() < 2) {
            throw ApiException.invalid("Choose at least two orders to batch", Map.of("orderIds", "choose at least two orders"));
        }
        if (ids.size() > MAX_BATCH) {
            throw ApiException.invalid("Too many orders for one batch",
                    Map.of("orderIds", "at most " + MAX_BATCH + " orders per batch"));
        }
        // Lowest order number first, so details are numbered the same way however the request listed them.
        List<OutboundOrder> batch = new ArrayList<>(orders.findAllById(ids));
        if (batch.size() != ids.size()) {
            throw ApiException.notFound("Some orders were not found");
        }
        batch.sort(Comparator.comparing(OutboundOrder::getOrderNumber));
        List<String> blocked = batch.stream()
                .filter(o -> o.getStatus() == OrderStatus.CANCELLED || o.getStatus() == OrderStatus.ALLOCATED)
                .map(o -> o.getOrderNumber() + " is " + (o.getStatus() == OrderStatus.CANCELLED ? "cancelled" : "already fully allocated"))
                .toList();
        if (!blocked.isEmpty()) {
            throw ApiException.conflict("Some orders can't be allocated: " + String.join("; ", blocked) + ".",
                    Map.of("orderIds", String.join("; ", blocked)));
        }
        PickTask task = allocationService.allocateBatch(batch);
        orders.flush();
        return new BatchAllocation(task == null ? null : TaskView.of(task),
                batch.stream().map(OutboundViews.OrderRef::of).toList());
    }

    /**
     * Result of allocating orders as a batch.
     *
     * @param task   the batch pick task, or null if no stock could be reserved
     * @param orders each order in the batch with its status afterwards
     */
    public record BatchAllocation(TaskView task, List<OutboundViews.OrderRef> orders) {
    }

    /** Cancels an order: releases its stock and cancels its open picks. */
    public OrderDetail cancel(UUID id) {
        OutboundOrder order = load(id);
        if (order.getStatus() == OrderStatus.CANCELLED) {
            throw ApiException.conflict("Order " + order.getOrderNumber() + " is already cancelled.", Map.of());
        }
        // On a batch task only this order's details are cancelled; the other orders still get picked.
        List<String> touched = new ArrayList<>();
        int cancelledDetails = 0;
        for (PickTask task : tasks.findByOrderId(id)) {
            int n = task.cancelFor(order);
            if (n > 0) {
                touched.add(task.getTaskNumber());
                cancelledDetails += n;
            }
        }
        tasks.flush();
        List<Allocation> reserved = allocations.findByLine_Order_Id(id);
        long released = reserved.stream().mapToLong(Allocation::getQuantity).sum();
        allocations.deleteAll(reserved);
        order.getLines().forEach(OrderLine::cancel);
        order.refreshStatus();
        String what = touched.isEmpty() ? "no open picks"
                : "%d detail%s of pick task%s %s".formatted(cancelledDetails, cancelledDetails == 1 ? "" : "s",
                        touched.size() == 1 ? "" : "s", String.join(", ", touched));
        events.save(new OrderEvent(order, Type.CANCELLED,
                "Order cancelled: released %d EA and cancelled %s".formatted(released, what)));
        orders.flush();
        return detail(order);
    }

    private OutboundOrder load(UUID id) {
        return orders.findById(id).orElseThrow(() -> ApiException.notFound("Order not found"));
    }

    private OrderDetail detail(OutboundOrder o) {
        return new OrderDetail(o.getId(), o.getOrderNumber(), o.getCustomerName(), o.getRequestedShipDate(),
                o.getPriority(), o.getStatus(),
                o.getLines().stream().map(LineView::of).toList(),
                allocationViews(allocations.findByLine_Order_IdOrderByCreatedAtAsc(o.getId())).stream()
                        .sorted((a, b) -> Integer.compare(a.lineNumber(), b.lineNumber()))
                        .toList(),
                tasks.findByOrderId(o.getId()).stream().map(TaskView::of).toList(),
                events.findByOrder_IdOrderByOccurredAtAsc(o.getId()).stream().map(EventView::of).toList(),
                o.getCreatedAt(), o.getUpdatedAt(), o.getVersion());
    }
}
