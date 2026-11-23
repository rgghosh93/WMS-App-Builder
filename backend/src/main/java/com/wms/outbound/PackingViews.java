package com.wms.outbound;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.wms.common.measure.WeightUom;
import com.wms.outbound.OutboundViews.ItemRef;
import com.wms.outbound.OutboundViews.LocationRef;
import com.wms.outbound.OutboundViews.LpnRef;
import com.wms.outbound.OutboundViews.OrderRef;
import com.wms.masterdata.item.ItemScan;

/** Read models for packing. */
public final class PackingViews {

    private PackingViews() {
    }

    /** @param waitingTasks containers at the station that are not fully packed */
    public record StationView(UUID id, String locationId, long waitingTasks) {
    }

    /** What is waiting at a station, most urgent first. */
    public record StationQueue(LocationRef station, List<PackTaskView> tasks) {
    }

    public record PickTaskRef(UUID id, String taskNumber) {
    }

    /**
     * Something in the container to pack.
     *
     * @param shortQuantity eaches reported missing at packing
     * @param itemScans     what can be scanned for the item, and the unit each stands for
     */
    public record PackDetailView(UUID id, int detailNumber, OrderRef order, int lineNumber, ItemRef item, String lotNumber,
                                 String serialNumber, int quantity, int packedQuantity, int shortQuantity, int remaining,
                                 List<ItemScan> itemScans) {
        static PackDetailView of(PackTaskDetail d) {
            return new PackDetailView(d.getId(), d.getDetailNumber(), OrderRef.of(d.getOrder()), d.getLine().getLineNumber(),
                    ItemRef.of(d.getItem()), d.getLotNumber(), d.getSerialNumber(), d.getQuantity(), d.getPackedQuantity(),
                    d.getShortQuantity(), d.remaining(), ItemScan.of(d.getItem()));
        }
    }

    public record CartonLineView(UUID detailId, int lineNumber, ItemRef item, String lotNumber, String serialNumber, int quantity) {
        static CartonLineView of(PackCartonLine l) {
            PackTaskDetail d = l.getDetail();
            return new CartonLineView(d.getId(), d.getLine().getLineNumber(), ItemRef.of(d.getItem()), d.getLotNumber(),
                    d.getSerialNumber(), l.getQuantity());
        }
    }

    public record CartonView(UUID id, LpnRef container, String containerType, OrderRef order, CartonStatus status,
                             BigDecimal weight, WeightUom weightUom, int totalQuantity, List<CartonLineView> contents,
                             Instant closedAt, String closedBy, String trackingNumber, Instant loadedAt) {
        static CartonView of(PackCarton c) {
            return new CartonView(c.getId(), LpnRef.of(c.getContainer()), c.getContainer().getContainerType().getContainerTypeId(),
                    OrderRef.of(c.getOrder()), c.getStatus(), c.getWeight(), c.getWeightUom(), c.totalQuantity(),
                    c.getLines().stream().map(CartonLineView::of)
                            .sorted(Comparator.comparing(CartonLineView::lineNumber).thenComparing(l -> l.item().itemId())
                                    .thenComparing(l -> l.detailId().toString())).toList(),
                    c.getClosedAt(), c.getClosedBy() == null ? null : c.getClosedBy().getDisplayName(),
                    c.getTrackingNumber(), c.getLoadedAt());
        }
    }

    /**
     * A packing task with everything in it.
     *
     * @param container     the container being packed out of
     * @param canPackAsIs   the container is itself a carton holding one order, so it can ship without repacking
     */
    public record PackTaskView(UUID id, String taskNumber, PackTaskStatus status, LocationRef station, LpnRef container,
                               String containerType, PickTaskRef pickTask, List<OrderRef> orders, String assignedTo,
                               UUID assignedToId, int totalQuantity, int packedQuantity, int shortQuantity,
                               boolean canPackAsIs, List<PackDetailView> details, List<CartonView> cartons,
                               Instant createdAt, Instant startedAt, Instant completedAt) {
        static PackTaskView of(PackTask t) {
            List<PackDetailView> details = t.getDetails().stream().map(PackDetailView::of).toList();
            return new PackTaskView(t.getId(), t.getTaskNumber(), t.getStatus(), LocationRef.of(t.getStation()),
                    LpnRef.of(t.getContainer()), t.getContainer().getContainerType().getDescription(),
                    new PickTaskRef(t.getPickTask().getId(), t.getPickTask().getTaskNumber()),
                    t.orders().stream().map(OrderRef::of).toList(),
                    t.getAssignedTo() == null ? null : t.getAssignedTo().getDisplayName(),
                    t.getAssignedTo() == null ? null : t.getAssignedTo().getId(),
                    details.stream().mapToInt(PackDetailView::quantity).sum(),
                    details.stream().mapToInt(PackDetailView::packedQuantity).sum(),
                    details.stream().mapToInt(PackDetailView::shortQuantity).sum(),
                    PackingService.canPackAsIs(t), details, t.getCartons().stream().map(CartonView::of).toList(),
                    t.getCreatedAt(), t.getStartedAt(), t.getCompletedAt());
        }
    }
}
