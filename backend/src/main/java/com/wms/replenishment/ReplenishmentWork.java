package com.wms.replenishment;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.wms.inventory.InventoryRecord;
import com.wms.inventory.InventoryRepository;
import com.wms.inventory.LocationLocks;
import com.wms.replenishment.ReplenishmentTask.Status;
import com.wms.work.Directions;
import com.wms.work.WorkItem;
import com.wms.work.WorkKind;
import com.wms.work.WorkSource;

/** Replenishment in the task queue. */
@Component
class ReplenishmentWork implements WorkSource {

    private final ReplenishmentTaskRepository tasks;
    private final InventoryRepository inventory;
    private final LocationLocks locks;
    private final Directions directions;

    ReplenishmentWork(ReplenishmentTaskRepository tasks, InventoryRepository inventory, LocationLocks locks, Directions directions) {
        this.tasks = tasks;
        this.inventory = inventory;
        this.locks = locks;
        this.directions = directions;
    }

    @Override
    public WorkKind kind() {
        return WorkKind.REPLENISHMENT;
    }

    @Override
    public List<WorkItem> active() {
        List<ReplenishmentTask> all = tasks.findByStatusInOrderByPriorityAscCreatedAtAscTaskNumberAsc(ReplenishmentTask.ACTIVE);
        Set<UUID> places = new HashSet<>();
        all.forEach(t -> places.addAll(List.of(t.getFromLocation().getId(), t.getToLocation().getId())));
        Set<UUID> locked = places.isEmpty() ? Set.of() : locks.locked(places);
        Set<UUID> usable = inventory.findAllById(all.stream().map(ReplenishmentTask::getInventoryId).distinct().toList()).stream()
                .filter(InventoryRecord::isUsable).map(InventoryRecord::getId).collect(Collectors.toSet());
        return all.stream().map(t -> {
            boolean started = t.getStatus() == Status.IN_PROGRESS;
            return new WorkItem(WorkKind.REPLENISHMENT, t.getId(), t.getTaskNumber(),
                    "Bring %,d EA of %s to %s".formatted(t.remaining(), t.getItem().getItemId(), t.getToLocation().getLocationId()),
                    t.getFromLocation(), t.getFromLpn(),
                    usable.contains(t.getInventoryId()) && !locked.contains(t.getFromLocation().getId()) && !locked.contains(t.getToLocation().getId()),
                    started ? t.getAssignedTo().getId() : null, started ? t.getAssignedTo().getDisplayName() : null,
                    directions.own(WorkKind.REPLENISHMENT, ReplenishmentTask.adjust(t.getReason())), t.getCreatedAt(), t.getStartedAt(),
                    "/replenishment/tasks?q=" + t.getTaskNumber());
        }).toList();
    }
}
