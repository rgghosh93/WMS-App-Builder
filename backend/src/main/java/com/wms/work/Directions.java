package com.wms.work;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** What was said about tasks, for whoever hands them out. */
@Component
@Transactional(readOnly = true)
public class Directions {

    private static final int URGENT = 1;
    private static final int LAST = 9;
    private static final int NORMAL = 5;

    private final TaskDirectionRepository directions;
    private final WorkSettingRepository settings;

    Directions(TaskDirectionRepository directions, WorkSettingRepository settings) {
        this.directions = directions;
        this.settings = settings;
    }

    /** @param priority the task's own */
    public record Candidate(UUID id, int priority) {
    }

    /** The priority tasks of a kind have of themselves. @param adjust more urgent if less than 0 */
    public int own(WorkKind kind, int adjust) {
        int base = settings.findById(kind).map(WorkSetting::getPriority).orElse(NORMAL);
        return Math.clamp(base + adjust, URGENT, LAST);
    }

    /**
     * Waiting tasks for someone to do: what they were told to do first, then the most urgent, otherwise in the order
     * given. Without those someone else is to do or was just offered.
     */
    public List<UUID> arrange(UUID userId, List<Candidate> waiting) {
        Map<UUID, TaskDirection> said = of(waiting.stream().map(Candidate::id).toList());
        return waiting.stream().filter(c -> said.get(c.id()) == null || !said.get(c.id()).isForSomeoneElse(userId))
                .sorted(Comparator.comparing((Candidate c) -> said.get(c.id()) == null || !said.get(c.id()).isAssignedTo(userId))
                        .thenComparingInt(c -> priority(said.get(c.id()), c.priority())))
                .map(Candidate::id).toList();
    }

    /** Someone starts a task, so what they were offered is there for others again. */
    @Transactional
    public void starting(UUID userId) {
        directions.unclaim(userId);
    }

    /** Who else is to do a task, if anyone is. */
    public Optional<String> assignedToOther(UUID taskId, UUID userId) {
        return directions.findById(taskId).filter(d -> d.assignee() != null && !d.isAssignedTo(userId)).map(d -> d.assignee().getDisplayName());
    }

    /** @param own the priority each task has of itself */
    public Map<UUID, Integer> priorities(Map<UUID, Integer> own) {
        Map<UUID, TaskDirection> said = of(own.keySet());
        Map<UUID, Integer> result = new HashMap<>();
        own.forEach((id, priority) -> result.put(id, priority(said.get(id), priority)));
        return result;
    }

    Map<UUID, TaskDirection> of(Collection<UUID> ids) {
        Map<UUID, TaskDirection> result = new HashMap<>();
        if (!ids.isEmpty()) {
            directions.findFor(ids).forEach(d -> result.put(d.getTaskId(), d));
        }
        return result;
    }

    static int priority(TaskDirection said, int own) {
        return said == null || said.getPriority() == null ? own : said.getPriority();
    }
}
