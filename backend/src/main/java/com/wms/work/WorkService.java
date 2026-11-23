package com.wms.work;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wms.common.ActiveStatus;
import com.wms.common.api.ApiException;
import com.wms.users.AppUser;
import com.wms.users.AppUserRepository;
import com.wms.users.UserWork;
import com.wms.work.WorkViews.KindSummary;
import com.wms.work.WorkViews.Setting;
import com.wms.work.WorkViews.TaskRow;
import com.wms.work.WorkViews.Worker;

/** All work in one queue: what there is to do, how urgent it is, and who is to do it. */
@Service
@Transactional
public class WorkService {

    private static final int MAX_AT_ONCE = 200;

    /** Most urgent first, then waiting longest. */
    static final Comparator<TaskRow> URGENCY = Comparator.comparingInt(TaskRow::priority).thenComparing(TaskRow::createdAt)
            .thenComparing(TaskRow::number);

    private final List<WorkSource> sources;
    private final Directions said;
    private final TaskDirectionRepository directions;
    private final WorkSettingRepository settings;
    private final WorkControlRepository control;
    private final AppUserRepository users;

    WorkService(List<WorkSource> sources, Directions said, TaskDirectionRepository directions, WorkSettingRepository settings,
                WorkControlRepository control, AppUserRepository users) {
        this.sources = sources;
        this.said = said;
        this.directions = directions;
        this.settings = settings;
        this.control = control;
        this.users = users;
    }

    public enum State { WAITING, UNASSIGNED, ASSIGNED, STARTED }

    /** @param person a task they are to do or are doing */
    public record Filter(String q, WorkKind kind, State state, UUID person) {
    }

    public record Prioritize(List<UUID> ids, Integer priority) {
    }

    /** @param userId nobody in particular if null */
    public record Assign(List<UUID> ids, UUID userId) {
    }

    public record ChangeSetting(WorkKind kind, Integer priority, Boolean offered) {
    }

    // ---------- Looking ----------

    @Transactional(readOnly = true)
    public Page<TaskRow> queue(Filter filter, Pageable page) {
        List<WorkItem> items = items(filter.kind() == null ? null : Set.of(filter.kind()));
        Map<UUID, UUID> doing = items.stream().filter(WorkItem::started).collect(Collectors.toMap(WorkItem::id, WorkItem::userId));
        String q = filter.q() == null ? "" : filter.q().trim().toLowerCase(Locale.ROOT);
        List<TaskRow> found = rows(items).stream()
                .filter(r -> filter.state() == null || switch (filter.state()) {
                    case WAITING -> !r.started();
                    case UNASSIGNED -> !r.started() && r.assignedTo() == null;
                    case ASSIGNED -> !r.started() && r.assignedTo() != null;
                    case STARTED -> r.started();
                })
                .filter(r -> filter.person() == null || (r.started() ? filter.person().equals(doing.get(r.id()))
                        : r.assignedTo() != null && filter.person().equals(r.assignedTo().id())))
                .filter(r -> q.isEmpty() || Stream.of(r.number(), r.what(), r.location(), r.lpn(), r.worker(),
                        r.assignedTo() == null ? null : r.assignedTo().name()).filter(Objects::nonNull)
                        .anyMatch(t -> t.toLowerCase(Locale.ROOT).contains(q)))
                .sorted(URGENCY).toList();
        int from = (int) Math.min(page.getOffset(), found.size());
        return new PageImpl<>(found.subList(from, Math.min(from + page.getPageSize(), found.size())), page, found.size());
    }

    @Transactional(readOnly = true)
    public List<KindSummary> summary() {
        Map<WorkKind, List<TaskRow>> byKind = rows(items(null)).stream().collect(Collectors.groupingBy(TaskRow::kind));
        return Arrays.stream(WorkKind.values()).map(k -> {
            List<TaskRow> of = byKind.getOrDefault(k, List.of());
            List<TaskRow> waiting = of.stream().filter(r -> !r.started()).toList();
            return new KindSummary(k, k.label(), waiting.size(), waiting.stream().filter(r -> r.assignedTo() != null).count(),
                    of.size() - waiting.size(), waiting.stream().map(TaskRow::createdAt).min(Comparator.naturalOrder()).orElse(null));
        }).toList();
    }

    @Transactional(readOnly = true)
    public List<Worker> people() {
        List<WorkItem> items = items(null);
        List<TaskRow> rows = rows(items);
        return users.findAll().stream().filter(u -> u.getStatus() == ActiveStatus.ACTIVE && !kinds(u).isEmpty())
                .sorted(Comparator.comparing(AppUser::getDisplayName, String.CASE_INSENSITIVE_ORDER))
                .map(u -> new Worker(u.getId(), u.getDisplayName(), kinds(u),
                        items.stream().filter(i -> u.getId().equals(i.userId())).map(i -> i.kind().label() + " " + i.number()).toList(),
                        rows.stream().filter(r -> !r.started() && r.assignedTo() != null && r.assignedTo().id().equals(u.getId())).count()))
                .toList();
    }

    private static List<WorkKind> kinds(AppUser user) {
        var held = user.permissions();
        return Arrays.stream(WorkKind.values()).filter(k -> held.contains(k.permission())).toList();
    }

    // ---------- Directing ----------

    /** @return how many tasks were changed */
    public int prioritize(Prioritize request) {
        if (request.priority() != null && (request.priority() < 1 || request.priority() > 9)) {
            throw ApiException.invalid("Choose a priority from 1 to 9", Map.of("priority", "from 1 to 9"));
        }
        control.lock();
        List<WorkItem> chosen = chosen(request.ids());
        chosen.forEach(i -> direction(i).setPriority(request.priority()));
        directions.flush();
        return chosen.size();
    }

    /** @return how many tasks were changed */
    public int assign(Assign request) {
        AppUser user = request.userId() == null ? null : users.findById(request.userId())
                .filter(u -> u.getStatus() == ActiveStatus.ACTIVE)
                .orElseThrow(() -> ApiException.invalid("Choose someone who is active", Map.of("userId", "choose someone who is active")));
        control.lock();
        List<WorkItem> chosen = chosen(request.ids());
        for (WorkItem i : chosen) {
            if (!i.kind().isAssignable()) {
                throw ApiException.conflict(i.number() + " is done by whoever works at " + i.at().getLocationId() + ".", Map.of());
            }
            if (i.started()) {
                throw ApiException.conflict(i.userName() + " is already doing " + i.number() + ".", Map.of());
            }
            if (user != null && !user.permissions().contains(i.kind().permission())) {
                throw ApiException.conflict(user.getDisplayName() + " may not do " + i.number() + ": it takes the permission \""
                        + i.kind().permission().label() + "\".", Map.of("userId", "may not do " + i.number()));
            }
        }
        chosen.forEach(i -> direction(i).assign(user));
        directions.flush();
        return chosen.size();
    }

    private List<WorkItem> chosen(List<UUID> ids) {
        Set<UUID> wanted = ids == null ? Set.of() : Set.copyOf(ids);
        if (wanted.isEmpty() || wanted.size() > MAX_AT_ONCE) {
            throw ApiException.invalid(wanted.isEmpty() ? "Choose tasks" : "At most " + MAX_AT_ONCE + " tasks at a time", Map.of("ids", "choose tasks"));
        }
        List<WorkItem> found = items(null).stream().filter(i -> wanted.contains(i.id())).toList();
        if (found.size() != wanted.size()) {
            throw ApiException.conflict("Some of those tasks are finished or cancelled. Reload and choose again.", Map.of());
        }
        return found;
    }

    private TaskDirection direction(WorkItem item) {
        return directions.findById(item.id()).orElseGet(() -> directions.save(new TaskDirection(item.id(), item.kind())));
    }

    // ---------- Settings ----------

    @Transactional(readOnly = true)
    public List<Setting> settings() {
        Map<WorkKind, WorkSetting> all = settings.findAll().stream().collect(Collectors.toMap(WorkSetting::getKind, Function.identity()));
        return Arrays.stream(WorkKind.values()).map(all::get).filter(Objects::nonNull).map(Setting::of).toList();
    }

    public List<Setting> change(List<ChangeSetting> changes) {
        for (ChangeSetting c : changes) {
            WorkSetting s = c.kind() == null ? null : settings.findById(c.kind()).orElse(null);
            if (s == null) {
                throw ApiException.invalid("Choose a kind of work", Map.of("kind", "choose a kind of work"));
            }
            if (!s.getKind().isForOrders()) {
                if (c.priority() == null || c.priority() < 1 || c.priority() > 9) {
                    throw ApiException.invalid("Choose a priority from 1 to 9", Map.of(s.getKind() + ".priority", "from 1 to 9"));
                }
                s.setPriority(c.priority());
            }
            s.setOffered(s.getKind().isAssignable() && Boolean.TRUE.equals(c.offered()));
        }
        settings.flush();
        return settings();
    }

    // ---------- Shared ----------

    /** @param kinds all if null */
    List<WorkItem> items(Collection<WorkKind> kinds) {
        List<WorkItem> all = new ArrayList<>();
        sources.stream().filter(s -> kinds == null || kinds.contains(s.kind())).forEach(s -> all.addAll(s.active()));
        return all;
    }

    List<TaskRow> rows(List<WorkItem> items) {
        Map<UUID, TaskDirection> of = said.of(items.stream().map(WorkItem::id).toList());
        return items.stream().map(i -> TaskRow.of(i, of.get(i.id()))).toList();
    }

    Set<WorkKind> offered() {
        return settings.findAll().stream().filter(WorkSetting::isOffered).map(WorkSetting::getKind).collect(Collectors.toSet());
    }

    /** What someone who can't sign in any more would leave undone. */
    @Component
    static class Assigned implements UserWork {
        private final TaskDirectionRepository directions;
        private final List<WorkSource> sources;

        Assigned(TaskDirectionRepository directions, List<WorkSource> sources) {
            this.directions = directions;
            this.sources = sources;
        }

        @Override
        public List<String> inHand(UUID userId) {
            Set<UUID> theirs = directions.findByAssignedTo_Id(userId).stream().map(TaskDirection::getTaskId).collect(Collectors.toSet());
            if (theirs.isEmpty()) {
                return List.of();
            }
            long waiting = sources.stream().flatMap(s -> s.active().stream()).filter(i -> !i.started() && theirs.contains(i.id())).count();
            return waiting == 0 ? List.of() : List.of(waiting + (waiting == 1 ? " task" : " tasks") + " assigned");
        }
    }
}
