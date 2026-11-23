package com.wms.putaway;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.wms.common.ActiveStatus;
import com.wms.inventory.InventoryRecord;
import com.wms.inventory.InventoryStatus;
import com.wms.masterdata.container.Container;
import com.wms.masterdata.container.ContainerCategory;
import com.wms.masterdata.item.Item;
import com.wms.masterdata.location.Location;
import com.wms.masterdata.zone.Zone;

/** Read models for put-away. */
public final class PutawayViews {

    private PutawayViews() {
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

    public record ZoneRef(UUID id, String code, String name) {
        static ZoneRef of(Zone z) {
            return z == null ? null : new ZoneRef(z.getId(), z.getCode(), z.getName());
        }
    }

    public record LpnRef(UUID id, String lpnId, String containerType) {
        static LpnRef of(Container c) {
            return new LpnRef(c.getId(), c.getLpnId(), c.getContainerType().getDescription());
        }
    }

    public record LineView(ItemRef item, int quantity, String lotNumber, LocalDate expiryDate, InventoryStatus status) {
        static LineView of(InventoryRecord r) {
            return new LineView(ItemRef.of(r.getItem()), r.getQuantity(), r.getLotNumber(), r.getExpiryDate(), r.getStatus());
        }

        static LineView of(PutawayTaskLine l) {
            return new LineView(ItemRef.of(l.getItem()), l.getQuantity(), l.getLotNumber(), l.getExpiryDate(), l.getStockStatus());
        }
    }

    public record SkipView(LocationRef location, OverrideReason reason, String skippedBy, Instant skippedAt) {
        static SkipView of(PutawaySkip s) {
            return new SkipView(LocationRef.of(s.getLocation()), s.getReason(), s.getSkippedBy().getDisplayName(), s.getSkippedAt());
        }
    }

    /**
     * @param directedLocation where it should go; null until started, or if no rule found room
     * @param toLocation       where it went
     * @param lines            what is in it now, or what was put away
     */
    public record TaskView(UUID id, String taskNumber, PutawayTaskStatus status, LpnRef lpn, LocationRef fromLocation,
                           LocationRef directedLocation, String ruleName, PutawayHandling handling, LocationRef toLocation,
                           OverrideReason overrideReason, String assignedTo, List<LineView> lines, int totalQuantity,
                           List<SkipView> skips, Instant createdAt, Instant startedAt, Instant completedAt) {
        static TaskView of(PutawayTask t, List<LineView> lines) {
            return new TaskView(t.getId(), t.getTaskNumber(), t.getStatus(), LpnRef.of(t.getContainer()),
                    LocationRef.of(t.getFromLocation()), LocationRef.of(t.getDirectedLocation()), t.getRuleName(), t.getHandling(),
                    LocationRef.of(t.getToLocation()), t.getOverrideReason(),
                    t.getAssignedTo() == null ? null : t.getAssignedTo().getDisplayName(), lines,
                    lines.stream().mapToInt(LineView::quantity).sum(), t.getSkips().stream().map(SkipView::of).toList(),
                    t.getCreatedAt(), t.getStartedAt(), t.getCompletedAt());
        }
    }

    /**
     * Where someone is in put-away.
     *
     * @param task    what they are putting away, or null
     * @param waiting the LPNs waiting longest
     */
    public record PutawayState(TaskView task, long waitingCount, List<TaskView> waiting) {
    }

    public record RuleView(UUID id, int sequence, String name, ActiveStatus status, ItemRef item,
                           ContainerCategory containerCategory, InventoryStatus stockStatus, PutawayStrategy strategy,
                           LocationRef location, ZoneRef zone, PutawayHandling handling, long version) {
        static RuleView of(PutawayRule r) {
            return new RuleView(r.getId(), r.getSequence(), r.getName(), r.getStatus(),
                    r.getItem() == null ? null : ItemRef.of(r.getItem()), r.getContainerCategory(), r.getStockStatus(),
                    r.getStrategy(), LocationRef.of(r.getLocation()), ZoneRef.of(r.getZone()), r.getHandling(), r.getVersion());
        }
    }
}
