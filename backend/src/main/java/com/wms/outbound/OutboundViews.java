package com.wms.outbound;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.wms.inventory.InventoryRecord;
import com.wms.inventory.InventoryStatus;
import com.wms.masterdata.container.Container;
import com.wms.masterdata.item.Item;
import com.wms.masterdata.location.Location;

/** Read models for orders, allocations and pick tasks. */
public final class OutboundViews {

    private OutboundViews() {
    }

    public record ItemRef(UUID id, String itemId, String description) {
        static ItemRef of(Item i) {
            return new ItemRef(i.getId(), i.getItemId(), i.getDescription());
        }
    }

    public record LocationRef(UUID id, String locationId) {
        static LocationRef of(Location l) {
            return l == null ? null : new LocationRef(l.getId(), l.getLocationId());
        }
    }

    public record LpnRef(UUID id, String lpnId) {
        static LpnRef of(Container c) {
            return c == null ? null : new LpnRef(c.getId(), c.getLpnId());
        }
    }

    public record OrderRef(UUID id, String orderNumber, String customerName, OrderStatus status) {
        static OrderRef of(OutboundOrder o) {
            return new OrderRef(o.getId(), o.getOrderNumber(), o.getCustomerName(), o.getStatus());
        }
    }

    /** The task detail an allocation became: which task, which detail, and its status. */
    public record TaskRef(UUID id, String taskNumber, int detailNumber, PickTaskStatus status) {
        static TaskRef of(PickTaskDetail d) {
            return d == null ? null
                    : new TaskRef(d.getTask().getId(), d.getTask().getTaskNumber(), d.getDetailNumber(), d.getStatus());
        }
    }

    /**
     * One order in the list.
     *
     * @param orderedQuantity   eaches ordered across open lines
     * @param allocatedQuantity eaches with stock reserved
     * @param openTaskCount     pick tasks waiting to be done
     */
    public record OrderSummary(
            UUID id,
            String orderNumber,
            String customerName,
            LocalDate requestedShipDate,
            int priority,
            OrderStatus status,
            int lineCount,
            long orderedQuantity,
            long allocatedQuantity,
            long openTaskCount,
            Instant createdAt) {
    }

    public record LineView(
            UUID id,
            int lineNumber,
            ItemRef item,
            int orderedQuantity,
            int allocatedQuantity,
            String requestedLot,
            LineStatus status) {

        static LineView of(OrderLine l) {
            return new LineView(l.getId(), l.getLineNumber(), ItemRef.of(l.getItem()), l.getOrderedQuantity(),
                    l.getAllocatedQuantity(), l.getRequestedLot(), l.getStatus());
        }
    }

    /**
     * Stock reserved for an order line, where it is now, and the pick task made for it.
     *
     * @param task the pick task created from this allocation, if any
     */
    public record AllocationView(
            UUID id,
            OrderRef order,
            int lineNumber,
            ItemRef item,
            int quantity,
            LocationRef location,
            LpnRef container,
            String lotNumber,
            String serialNumber,
            LocalDate expiryDate,
            InventoryStatus inventoryStatus,
            TaskRef task,
            Instant createdAt) {

        static AllocationView of(Allocation a, PickTaskDetail task) {
            InventoryRecord r = a.getInventory();
            return new AllocationView(a.getId(), OrderRef.of(a.getLine().getOrder()), a.getLine().getLineNumber(),
                    ItemRef.of(a.getLine().getItem()), a.getQuantity(),
                    LocationRef.of(AllocationService.locationOf(r)), LpnRef.of(r.getContainer()),
                    r.getLotNumber(), r.getSerialNumber(), r.getExpiryDate(), r.getStatus(), TaskRef.of(task),
                    a.getCreatedAt());
        }
    }

    /** One step of a pick task: pick this allocation. */
    public record TaskDetailView(
            UUID id,
            int detailNumber,
            OrderRef order,
            int lineNumber,
            ItemRef item,
            int quantity,
            LocationRef fromLocation,
            LpnRef fromContainer,
            String lotNumber,
            String serialNumber,
            PickTaskStatus status,
            UUID allocationId) {

        static TaskDetailView of(PickTaskDetail d) {
            return new TaskDetailView(d.getId(), d.getDetailNumber(), OrderRef.of(d.getOrder()), d.getLine().getLineNumber(),
                    ItemRef.of(d.getItem()),
                    d.getQuantity(), LocationRef.of(d.getFromLocation()), LpnRef.of(d.getFromContainer()),
                    d.getLotNumber(), d.getSerialNumber(), d.getStatus(),
                    d.getAllocation() == null ? null : d.getAllocation().getId());
        }
    }

    /**
     * A pick task with its details.
     *
     * @param pickMethod    discrete (one order) or batch (several orders in one walk)
     * @param orders        the orders the task picks for, in detail order
     * @param lineCount     order lines the task picks for
     * @param locationCount places the picker has to visit
     */
    public record TaskView(
            UUID id,
            String taskNumber,
            PickMethod pickMethod,
            List<OrderRef> orders,
            PickTaskStatus status,
            int detailCount,
            int lineCount,
            int locationCount,
            int totalQuantity,
            List<TaskDetailView> details,
            Instant createdAt) {

        static TaskView of(PickTask t) {
            List<TaskDetailView> details = t.getDetails().stream().map(TaskDetailView::of).toList();
            return new TaskView(t.getId(), t.getTaskNumber(), t.getPickMethod(),
                    t.orders().stream().map(OrderRef::of).toList(), t.getStatus(), details.size(),
                    (int) details.stream().map(d -> d.order().id() + "#" + d.lineNumber()).distinct().count(),
                    (int) details.stream().map(d -> d.fromLocation().id()).distinct().count(),
                    t.totalQuantity(), details, t.getCreatedAt());
        }
    }

    public record EventView(Instant occurredAt, OrderEvent.Type type, String message) {
        static EventView of(OrderEvent e) {
            return new EventView(e.getOccurredAt(), e.getType(), e.getMessage());
        }
    }

    /** An order with its lines, the stock reserved for it, its pick tasks and what has happened to it. */
    public record OrderDetail(
            UUID id,
            String orderNumber,
            String customerName,
            LocalDate requestedShipDate,
            int priority,
            OrderStatus status,
            List<LineView> lines,
            List<AllocationView> allocations,
            List<TaskView> tasks,
            List<EventView> events,
            Instant createdAt,
            Instant updatedAt,
            long version) {
    }
}
