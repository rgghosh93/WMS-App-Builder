package com.wms.outbound;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.wms.inventory.LocationLocks;
import com.wms.users.AppUser;
import com.wms.work.WorkItem;
import com.wms.work.WorkKind;
import com.wms.work.WorkSource;

/** Picking and packing in the task queue. */
final class OutboundWork {

    private OutboundWork() {
    }

    @Component
    static class Picks implements WorkSource {
        private final PickTaskRepository tasks;
        private final LocationLocks locks;

        Picks(PickTaskRepository tasks, LocationLocks locks) {
            this.tasks = tasks;
            this.locks = locks;
        }

        @Override
        public WorkKind kind() {
            return WorkKind.PICK;
        }

        @Override
        public List<WorkItem> active() {
            List<PickTask> all = tasks.findByStatusIn(List.of(PickTaskStatus.OPEN, PickTaskStatus.IN_PROGRESS, PickTaskStatus.PICKED));
            Set<UUID> places = new HashSet<>();
            all.forEach(t -> open(t).forEach(d -> places.add(d.getFromLocation().getId())));
            Set<UUID> locked = places.isEmpty() ? Set.of() : locks.locked(places);
            return all.stream().filter(t -> t.getStatus() != PickTaskStatus.OPEN || !open(t).isEmpty()).map(t -> {
                List<PickTaskDetail> open = open(t);
                AppUser user = t.getStatus() == PickTaskStatus.OPEN ? null : t.getAssignedTo();
                int orders = t.orders().size();
                String what = t.getStatus() == PickTaskStatus.PICKED ? "Drop " + t.getToContainer().getLpnId() + " at a pack station"
                        : "Pick " + (orders == 1 ? t.orders().getFirst().getOrderNumber() : orders + " orders") + ": " + open.size()
                                + (open.size() == 1 ? " pick" : " picks") + (t.getPickZone() == null ? "" : " in " + t.getPickZone().getCode());
                return new WorkItem(WorkKind.PICK, t.getId(), t.getTaskNumber(), what,
                        open.stream().min(PickingService.WALK_ORDER).map(PickTaskDetail::getFromLocation).orElse(t.getDropLocation()),
                        t.getToContainer() == null ? null : t.getToContainer().getLpnId(),
                        open.stream().noneMatch(d -> locked.contains(d.getFromLocation().getId())),
                        user == null ? null : user.getId(), user == null ? null : user.getDisplayName(), t.priority(), t.getCreatedAt(),
                        t.getStartedAt(), "/picking/tasks/" + t.getId());
            }).toList();
        }

        private static List<PickTaskDetail> open(PickTask t) {
            return t.getDetails().stream().filter(d -> d.getStatus() == PickDetailStatus.OPEN).toList();
        }
    }

    @Component
    static class Packs implements WorkSource {
        private final PackTaskRepository tasks;

        Packs(PackTaskRepository tasks) {
            this.tasks = tasks;
        }

        @Override
        public WorkKind kind() {
            return WorkKind.PACK;
        }

        @Override
        public List<WorkItem> active() {
            return tasks.findByStatusNot(PackTaskStatus.COMPLETED).stream().map(t -> {
                AppUser user = t.getStatus() == PackTaskStatus.IN_PROGRESS ? t.getAssignedTo() : null;
                return new WorkItem(WorkKind.PACK, t.getId(), t.getTaskNumber(),
                        "Pack " + t.orders().stream().map(OutboundOrder::getOrderNumber).collect(Collectors.joining(", ")), t.getStation(),
                        t.getContainer().getLpnId(), true, user == null ? null : user.getId(), user == null ? null : user.getDisplayName(),
                        t.priority(), t.getCreatedAt(), t.getStartedAt(), "/pack-ship/packing-tasks/" + t.getId());
            }).toList();
        }
    }
}
