package com.wms.work;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.wms.users.AppUser;

/** Read models for the task queue. */
public final class WorkViews {

    private WorkViews() {
    }

    public record Person(UUID id, String name) {
        static Person of(AppUser u) {
            return u == null ? null : new Person(u.getId(), u.getDisplayName());
        }
    }

    /**
     * @param priority    as it counts now
     * @param ownPriority as it would be if nothing was said
     * @param prioritized whether something was said
     * @param assignedTo  who is to do it
     * @param worker      who is doing it
     * @param ready       whether it can be started now
     */
    public record TaskRow(WorkKind kind, String label, UUID id, String number, String what, String location, String lpn, int priority,
                          int ownPriority, boolean prioritized, Person assignedTo, String worker, boolean started, boolean ready,
                          boolean assignable, Instant createdAt, Instant startedAt, String link) {
        static TaskRow of(WorkItem i, TaskDirection d) {
            return new TaskRow(i.kind(), i.kind().label(), i.id(), i.number(), i.what(), i.at() == null ? null : i.at().getLocationId(),
                    i.lpn(), Directions.priority(d, i.priority()), i.priority(), d != null && d.getPriority() != null,
                    Person.of(d == null ? null : d.assignee()), i.userName(), i.started(), i.ready(), i.kind().isAssignable(), i.createdAt(),
                    i.startedAt(), i.link());
        }
    }

    /** @param oldest when the task waiting longest was created */
    public record KindSummary(WorkKind kind, String label, long waiting, long assigned, long started, Instant oldest) {
    }

    /**
     * Someone who works on devices.
     *
     * @param kinds    what they may do
     * @param doing    tasks they have started
     * @param assigned how many waiting tasks they are to do
     */
    public record Worker(UUID id, String name, List<WorkKind> kinds, List<String> doing, long assigned) {
    }

    /** @param priority null for work that is as urgent as its orders */
    public record Setting(WorkKind kind, String label, Integer priority, boolean offered, boolean canBeOffered) {
        static Setting of(WorkSetting s) {
            return new Setting(s.getKind(), s.getKind().label(), s.getPriority(), s.isOffered(), s.getKind().isAssignable());
        }
    }

    /**
     * @param doing   tasks they have started, to be finished first
     * @param offer   what to do next; null if there is nothing, or something to finish
     * @param waiting how many tasks they could do
     */
    public record Next(List<TaskRow> doing, TaskRow offer, long waiting) {
    }
}
