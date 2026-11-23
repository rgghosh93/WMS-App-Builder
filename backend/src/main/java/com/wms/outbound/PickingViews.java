package com.wms.outbound;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.wms.masterdata.container.ContainerType;
import com.wms.masterdata.item.ItemScan;
import com.wms.outbound.OutboundViews.ItemRef;

/** What a mobile device shows a picker. */
public final class PickingViews {

    private PickingViews() {
    }

    /** What the picker has to do next. */
    public enum Step {
        /** Scan an outbound container to start a task. */
        SCAN_CONTAINER,
        /** The LPN is new: say what kind of carton it is. */
        CHOOSE_CONTAINER_TYPE,
        /** Pick the directed task detail. */
        PICK,
        /** Everything is picked: drop the container at a pack station. */
        DROP
    }

    /**
     * The picker's place in the picking transaction. Only the part for the current step is set.
     *
     * @param waitingTasks tasks nobody has started yet
     */
    public record PickingState(Step step, long waitingTasks, TaskProgress task, PickDirective pick, DropDirective drop,
                               NewContainer newContainer) {
    }

    /** @param waiting tasks nobody has started there */
    public record PickZone(UUID id, String code, String name, long waiting) {
    }

    /** @param closedDetails details picked or closed short, out of {@code detailCount} not cancelled */
    public record TaskProgress(UUID id, String taskNumber, PickMethod pickMethod, String pickZone, int orderCount, String toLpnId,
                               String toContainerType, int closedDetails, int detailCount, boolean canRelease) {
    }

    /** A quantity in one unit, e.g. 4 CS of 6 EA each. */
    public record UomQuantity(String uom, int quantity, int eachesPerUnit) {
    }

    /**
     * One pick: go to the location, take the item, put it in the outbound container.
     *
     * @param fromLpnId      the container to take the stock from, if it is in one
     * @param serialNumber   the exact unit to take, for serial-tracked items
     * @param pickedQuantity eaches already picked for this detail
     * @param remaining      eaches still to pick
     * @param directed       the remaining quantity in the largest units that can be scanned
     */
    public record PickDirective(UUID detailId, int detailNumber, String orderNumber, String customerName, String locationId,
                                String fromLpnId, ItemRef item, String lotNumber, LocalDate expiryDate, String serialNumber,
                                int quantity, int pickedQuantity, int remaining, List<UomQuantity> directed,
                                List<ItemScan> itemScans) {
    }

    /** @param suggestedLocationId the pack station with the fewest containers waiting; null if there are no pack stations */
    public record DropDirective(String suggestedLocationId) {
    }

    /** A carton LPN seen for the first time, and the carton types it could be. */
    public record NewContainer(String lpnId, List<ContainerTypeOption> types) {
    }

    public record ContainerTypeOption(UUID id, String containerTypeId, String description) {
        static ContainerTypeOption of(ContainerType t) {
            return new ContainerTypeOption(t.getId(), t.getContainerTypeId(), t.getDescription());
        }
    }
}
