package com.wms.replenishment;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.wms.common.ActiveStatus;
import com.wms.masterdata.item.Item;
import com.wms.masterdata.item.ItemScan;
import com.wms.masterdata.location.Location;
import com.wms.masterdata.zone.Zone;

/** Read models for replenishment. */
public final class ReplenishmentViews {

    private ReplenishmentViews() {
    }

    public record ItemRef(UUID id, String itemId, String description) {
        static ItemRef of(Item i) {
            return new ItemRef(i.getId(), i.getItemId(), i.getDescription());
        }
    }

    public record LocationRef(UUID id, String locationId) {
        static LocationRef of(Location l) {
            return new LocationRef(l.getId(), l.getLocationId());
        }
    }

    public record ZoneRef(UUID id, String code, String name) {
        static ZoneRef of(Zone z) {
            return z == null ? null : new ZoneRef(z.getId(), z.getCode(), z.getName());
        }
    }

    /**
     * @param onHand   good stock of the item in the pick location; this and the rest is null for an inactive rule
     * @param reserved of that, for orders or other moves
     * @param coming   on its way there
     * @param waiting  orders are short of this much
     * @param needed   still to find, over and above what is coming
     */
    public record RuleView(UUID id, ItemRef item, LocationRef location, int minQuantity, int maxQuantity, int multiple, ZoneRef sourceZone,
                           boolean forOrders, ActiveStatus status, Integer onHand, Integer reserved, Integer coming, Integer waiting,
                           Integer needed, long version) {
        static RuleView of(ReplenishmentRule r, Replenisher.Level l) {
            return new RuleView(r.getId(), ItemRef.of(r.getItem()), LocationRef.of(r.getLocation()), r.getMinQuantity(), r.getMaxQuantity(),
                    r.getMultiple(), ZoneRef.of(r.getSourceZone()), r.isForOrders(), r.getStatus(), l == null ? null : l.onHand(),
                    l == null ? null : l.reserved(), l == null ? null : l.coming(), l == null ? null : l.waiting(),
                    l == null ? null : l.needed(), r.getVersion());
        }
    }

    public record TaskView(UUID id, String taskNumber, ReplenishmentTask.Status status, ReplenishmentTask.Reason reason, ItemRef item,
                           List<ItemScan> scans, String lotNumber, LocalDate expiryDate, LocationRef fromLocation, String fromLpn,
                           LocationRef toLocation, int quantity, int movedQuantity, int shortQuantity, String assignedTo, String note,
                           Instant createdAt, Instant startedAt, Instant endedAt) {
        static TaskView of(ReplenishmentTask t) {
            return of(t, List.of());
        }

        static TaskView of(ReplenishmentTask t, List<ItemScan> scans) {
            return new TaskView(t.getId(), t.getTaskNumber(), t.getStatus(), t.getReason(), ItemRef.of(t.getItem()), scans, t.getLotNumber(),
                    t.getExpiryDate(), LocationRef.of(t.getFromLocation()), t.getFromLpn(), LocationRef.of(t.getToLocation()),
                    t.getQuantity(), t.getMovedQuantity(), t.getShortQuantity(),
                    t.getAssignedTo() == null ? null : t.getAssignedTo().getDisplayName(), t.getNote(), t.getCreatedAt(), t.getStartedAt(),
                    t.getEndedAt());
        }
    }

    /**
     * Where someone is in replenishment.
     *
     * @param task    what they are moving, or null
     * @param waiting the most urgent tasks
     */
    public record DeviceState(TaskView task, long waitingCount, List<TaskView> waiting) {
    }
}
