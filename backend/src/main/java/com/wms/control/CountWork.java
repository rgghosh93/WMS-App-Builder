package com.wms.control;

import java.util.List;

import org.springframework.stereotype.Component;

import com.wms.control.CountTask.Status;
import com.wms.work.Directions;
import com.wms.work.WorkItem;
import com.wms.work.WorkKind;
import com.wms.work.WorkSource;

/** Counting in the task queue. Counts waiting to be reviewed aren't work on a device. */
@Component
class CountWork implements WorkSource {

    private final CountTaskRepository counts;
    private final Directions directions;

    CountWork(CountTaskRepository counts, Directions directions) {
        this.counts = counts;
        this.directions = directions;
    }

    /** Counting again is more urgent: the location stays locked until then. */
    static int own(Directions directions, CountTask t) {
        return directions.own(WorkKind.COUNT, t.getStatus() == Status.RECOUNT ? -2 : 0);
    }

    @Override
    public WorkKind kind() {
        return WorkKind.COUNT;
    }

    @Override
    public List<WorkItem> active() {
        return counts.findWaiting(List.of(Status.OPEN, Status.RECOUNT, Status.COUNTING)).stream().map(t -> {
            boolean started = t.getStatus() == Status.COUNTING;
            return new WorkItem(WorkKind.COUNT, t.getId(), t.getNumber(),
                    (t.getStatus() == Status.RECOUNT ? "Count " + t.getLocation().getLocationId() + " again" : "Count " + t.getLocation().getLocationId()),
                    t.getLocation(), null, true, started ? t.getCounterId() : null, started ? t.getCounter() : null, own(directions, t),
                    t.getPlannedAt(), started ? t.getStartedAt() : null, "/inventory/counts/" + t.getId());
        }).toList();
    }
}
