package com.wms.putaway;

import java.util.List;

import org.springframework.stereotype.Component;

import com.wms.work.Directions;
import com.wms.work.WorkItem;
import com.wms.work.WorkKind;
import com.wms.work.WorkSource;

/** Put-away in the task queue. */
@Component
class PutawayWork implements WorkSource {

    private final PutawayTaskRepository tasks;
    private final Directions directions;

    PutawayWork(PutawayTaskRepository tasks, Directions directions) {
        this.tasks = tasks;
        this.directions = directions;
    }

    @Override
    public WorkKind kind() {
        return WorkKind.PUTAWAY;
    }

    @Override
    public List<WorkItem> active() {
        int own = directions.own(WorkKind.PUTAWAY, 0);
        return tasks.findByStatusIn(List.of(PutawayTaskStatus.OPEN, PutawayTaskStatus.IN_PROGRESS)).stream()
                .map(t -> new WorkItem(WorkKind.PUTAWAY, t.getId(), t.getTaskNumber(),
                        "Put " + t.getContainer().getLpnId() + " away", t.getFromLocation(), t.getContainer().getLpnId(), true,
                        t.getAssignedTo() == null ? null : t.getAssignedTo().getId(),
                        t.getAssignedTo() == null ? null : t.getAssignedTo().getDisplayName(), own, t.getCreatedAt(), t.getStartedAt(),
                        "/putaway/tasks/" + t.getId()))
                .toList();
    }
}
