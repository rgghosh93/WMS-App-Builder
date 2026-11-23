package com.wms.putaway;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wms.common.Codes;
import com.wms.common.api.ApiException;
import com.wms.inbound.ReceivedLpns;
import com.wms.inventory.EmptiedLpns;
import com.wms.inventory.InventoryRecord;
import com.wms.inventory.InventoryRepository;
import com.wms.inventory.LocationLocks;
import com.wms.inventory.StockLedger;
import com.wms.inventory.StockLedger.Note;
import com.wms.inventory.StockLedger.Place;
import com.wms.inventory.StockTransaction;
import com.wms.masterdata.container.Container;
import com.wms.masterdata.container.ContainerRepository;
import com.wms.masterdata.container.ContainerStatus;
import com.wms.masterdata.location.Location;
import com.wms.masterdata.location.LocationRepository;
import com.wms.masterdata.location.LocationType;
import com.wms.putaway.Occupancy.Problem;
import com.wms.putaway.PutawayDirector.Direction;
import com.wms.putaway.PutawayViews.LineView;
import com.wms.putaway.PutawayViews.PutawayState;
import com.wms.putaway.PutawayViews.TaskView;
import com.wms.users.AppUser;
import com.wms.users.AppUserRepository;
import com.wms.work.Directions;
import com.wms.work.Directions.Candidate;
import com.wms.work.WorkKind;

/**
 * Moving received LPNs into storage. Receiving into an LPN makes a task for it; scanning the LPN
 * starts the task and decides where it goes; scanning the location puts it there.
 */
@Service
@Transactional
public class PutawayService implements ReceivedLpns, EmptiedLpns {

    private static final List<PutawayTaskStatus> NOT_DONE = List.of(PutawayTaskStatus.OPEN, PutawayTaskStatus.IN_PROGRESS);
    private static final int SHOW_WAITING = 20;

    private final PutawayTaskRepository tasks;
    private final PutawayControlRepository control;
    private final PutawayDirector director;
    private final ContainerRepository containers;
    private final InventoryRepository inventory;
    private final LocationRepository locations;
    private final AppUserRepository users;
    private final StockLedger ledger;
    private final LocationLocks locks;
    private final Directions directions;

    PutawayService(PutawayTaskRepository tasks, PutawayControlRepository control, PutawayDirector director,
                   ContainerRepository containers, InventoryRepository inventory, LocationRepository locations,
                   AppUserRepository users, StockLedger ledger, LocationLocks locks, Directions directions) {
        this.tasks = tasks;
        this.control = control;
        this.director = director;
        this.containers = containers;
        this.inventory = inventory;
        this.locations = locations;
        this.users = users;
        this.ledger = ledger;
        this.locks = locks;
        this.directions = directions;
    }

    /**
     * @param reason    why not the directed location, if it is another one
     * @param allowOver put it there although there is no room by the books
     * @param handling  keep or empty the LPN; as directed if left out
     */
    public record Confirm(String locationId, OverrideReason reason, Boolean allowOver, PutawayHandling handling) {
    }

    // ---------- From receiving ----------

    @Override
    public void received(Container lpn, Location at) {
        PutawayTask task = tasks.findFirstByContainer_IdAndStatusIn(lpn.getId(), NOT_DONE).orElse(null);
        if (task == null) {
            create(lpn, at);
        } else if (task.getStatus() == PutawayTaskStatus.OPEN) {
            task.waitAt(at);
        }
    }

    @Override
    public void emptied(Container lpn) {
        tasks.findFirstByContainer_IdAndStatusIn(lpn.getId(), List.of(PutawayTaskStatus.OPEN)).ifPresent(PutawayTask::cancel);
    }

    private PutawayTask create(Container lpn, Location at) {
        return tasks.save(new PutawayTask(UUID.randomUUID(), "PA%06d".formatted(tasks.nextNumber()), lpn, at));
    }

    // ---------- Looking ----------

    @Transactional(readOnly = true)
    public Page<TaskView> search(PutawaySearch search, Pageable pageable) {
        Page<PutawayTask> page = tasks.findAll(search.toSpecification(), pageable);
        Map<UUID, TaskView> views = views(page.getContent());
        return page.map(t -> views.get(t.getId()));
    }

    @Transactional(readOnly = true)
    public TaskView get(UUID id) {
        PutawayTask task = tasks.findById(id).orElseThrow(() -> ApiException.notFound("Put-away task not found"));
        return views(List.of(task)).get(id);
    }

    @Transactional(readOnly = true)
    public PutawayState state(UUID userId) {
        PutawayTask mine = tasks.findFirstByAssignedTo_IdAndStatus(userId, PutawayTaskStatus.IN_PROGRESS).orElse(null);
        int own = directions.own(WorkKind.PUTAWAY, 0);
        List<UUID> waiting = directions.arrange(userId, tasks.findOpenIds().stream().map(id -> new Candidate(id, own)).toList());
        if (mine != null) {
            return new PutawayState(views(List.of(mine)).get(mine.getId()), waiting.size(), List.of());
        }
        List<UUID> shown = waiting.subList(0, Math.min(waiting.size(), SHOW_WAITING));
        Map<UUID, TaskView> views = views(tasks.findAllById(shown));
        return new PutawayState(null, waiting.size(), shown.stream().map(views::get).toList());
    }

    // ---------- Working ----------

    /**
     * Takes an LPN and is told where to put it.
     *
     * @param takeOver if someone else has it, carry on with their task
     */
    public PutawayState start(UUID userId, String scannedLpn, boolean takeOver) {
        String lpnId = Codes.normalize(scannedLpn);
        if (lpnId == null) {
            throw invalid("lpnId", "Scan an LPN");
        }
        control.lock();
        Container lpn = containers.findLockedByLpnId(lpnId).orElseThrow(() -> invalid("lpnId", lpnId + " is not a known LPN"));
        PutawayTask task = tasks.findFirstByContainer_IdAndStatusIn(lpn.getId(), NOT_DONE).orElse(null);
        PutawayTask mine = tasks.findFirstByAssignedTo_IdAndStatus(userId, PutawayTaskStatus.IN_PROGRESS).orElse(null);
        if (mine != null && (task == null || !mine.getId().equals(task.getId()))) {
            throw ApiException.conflict("Put away " + mine.getContainer().getLpnId() + " first.", Map.of());
        }
        AppUser user = users.getReferenceById(userId);
        if (task != null && task.getStatus() == PutawayTaskStatus.IN_PROGRESS) {
            if (!task.isAssignedTo(userId)) {
                String other = task.getAssignedTo().getDisplayName();
                if (!takeOver) {
                    throw ApiException.conflict(other + " is putting " + lpnId + " away.", Map.of("assignedTo", other));
                }
                task.assignTo(user);
                tasks.flush();
            }
            return state(userId);
        }
        if (task != null && !takeOver) {
            directions.assignedToOther(task.getId(), userId).ifPresent(other -> {
                throw ApiException.conflict(lpnId + " is assigned to " + other + ".", Map.of("assignedTo", other));
            });
        }
        Location at = lpn.getCurrentLocation();
        if (lpn.getParent() != null) {
            throw invalid("lpnId", lpnId + " is inside " + lpn.getParent().getLpnId() + ". Scan that instead");
        }
        if (at == null || !at.getType().isForReceiving()) {
            throw invalid("lpnId", lpnId + (at == null ? " is not at a receiving location" : " is already at " + at.getLocationId()));
        }
        // What is loaded for shipping stands at doors too, and must not be put away.
        if (task == null && at.getType() != LocationType.RECEIVING) {
            throw invalid("lpnId", lpnId + " was not received at " + at.getLocationId() + ", so it isn't put away");
        }
        if (lpn.getStatus() != ContainerStatus.IN_USE) {
            throw invalid("lpnId", lpnId + (lpn.getStatus() == ContainerStatus.ON_HOLD ? " is on hold" : " has nothing to put away"));
        }
        Load load = director.load(lpn);
        if (load.stock().isEmpty()) {
            throw invalid("lpnId", lpnId + " has nothing to put away");
        }
        if (task == null) {
            task = create(lpn, at);
        }
        directions.starting(userId);
        task.waitAt(at);
        task.start(user);
        direct(task, load);
        // It travels with whoever has it, so it is nowhere until it is put down.
        lpn.setCurrentLocation(null);
        tasks.flush();
        return state(userId);
    }

    /**
     * The directed location can't be used: go somewhere else.
     *
     * @param locationId the location that can't be used, so sending this twice doesn't pass over two
     */
    public PutawayState redirect(UUID userId, UUID taskId, String locationId, OverrideReason reason) {
        control.lock();
        PutawayTask task = active(userId, taskId);
        if (reason == null) {
            throw invalid("reason", "Say why it can't be used");
        }
        if (task.getDirectedLocation() == null || !task.getDirectedLocation().getLocationId().equals(Codes.normalize(locationId))) {
            return state(userId);
        }
        task.skip(reason, users.getReferenceById(userId));
        direct(task, director.load(task.getContainer()));
        tasks.flush();
        return state(userId);
    }

    /** Puts the LPN, or what is in it, in a location. */
    public PutawayState confirm(UUID userId, UUID taskId, Confirm request) {
        control.lock();
        PutawayTask task = tasks.findLocked(taskId).orElseThrow(() -> ApiException.notFound("Put-away task not found"));
        String code = Codes.normalize(request.locationId());
        if (task.getStatus() == PutawayTaskStatus.COMPLETED && task.isAssignedTo(userId)
                && task.getToLocation().getLocationId().equals(code)) {
            return state(userId); // sent twice
        }
        task = active(userId, taskId);
        Location to = code == null ? null : locations.findByLocationId(code).orElse(null);
        if (to == null) {
            throw invalid("locationId", code == null ? "Scan a location" : code + " is not a known location");
        }
        if (to.getType() != LocationType.STORAGE) {
            throw invalid("locationId", code + " is not a storage location");
        }
        if (locks.isLocked(to.getId())) {
            throw invalid("locationId", code + " is being counted. Choose another location");
        }
        Container lpn = containers.findLockedByLpnId(task.getContainer().getLpnId()).orElseThrow();
        Load load = director.load(lpn);
        PutawayHandling handling = Objects.requireNonNullElse(request.handling(), task.getHandling());
        if (handling == PutawayHandling.EMPTY_LPN && !load.nested().isEmpty()) {
            throw invalid("handling", lpn.getLpnId() + " holds other containers, so it is put away whole");
        }
        Location directed = task.getDirectedLocation();
        boolean elsewhere = directed != null && !directed.getId().equals(to.getId());
        if (elsewhere && request.reason() == null) {
            throw ApiException.conflict(lpn.getLpnId() + " should go to " + directed.getLocationId() + ", not " + code + ".",
                    Map.of("directed", directed.getLocationId()));
        }
        List<Problem> problems = director.occupancy(List.of(to), task).get(to.getId()).problems(to, load, handling);
        for (Problem p : problems) {
            if (p.hard()) {
                throw invalid("locationId", p.message());
            }
        }
        if (!problems.isEmpty() && !Boolean.TRUE.equals(request.allowOver())) {
            String message = problems.stream().map(Problem::message).collect(Collectors.joining(". "));
            throw ApiException.conflict(message + ".", Map.of("capacity", message));
        }
        for (InventoryRecord r : load.stock()) {
            task.getLines().add(new PutawayTaskLine(task, r));
            ledger.log(StockTransaction.Type.PUTAWAY, r, r.getQuantity(), Place.at(task.getFromLocation(), r.getContainer()),
                    Place.at(to, handling == PutawayHandling.KEEP_LPN ? r.getContainer() : null),
                    Note.by(userId, task.getTaskNumber()));
        }
        if (handling == PutawayHandling.KEEP_LPN) {
            lpn.setCurrentLocation(to);
        } else {
            empty(lpn, load.stock(), to);
        }
        task.complete(to, handling, elsewhere ? request.reason() : null);
        tasks.flush();
        return state(userId);
    }

    /** Puts the LPN back where it was received, for someone else. */
    public PutawayState release(UUID userId, UUID taskId) {
        control.lock();
        PutawayTask task = active(userId, taskId);
        Container lpn = containers.findLockedByLpnId(task.getContainer().getLpnId()).orElseThrow();
        lpn.setCurrentLocation(task.getFromLocation());
        task.release();
        tasks.flush();
        return state(userId);
    }

    private void direct(PutawayTask task, Load load) {
        Set<UUID> avoid = task.getSkips().stream().map(s -> s.getLocation().getId()).collect(Collectors.toSet());
        Direction direction = director.direct(task, load, avoid);
        task.direct(direction.location(), direction.ruleName(), direction.handling());
    }

    /** Puts the stock loose in a location, adding to what is the same, and takes the LPN out of use. */
    private void empty(Container lpn, Collection<InventoryRecord> stock, Location to) {
        List<InventoryRecord> there = inventory.findByLocation_IdIn(List.of(to.getId()));
        for (InventoryRecord r : stock) {
            InventoryRecord same = there.stream().filter(r::mergesWith).findFirst().orElse(null);
            if (same == null) {
                r.placeIn(to);
            } else {
                same.setQuantity(same.getQuantity() + r.getQuantity());
                inventory.delete(r);
            }
        }
        lpn.setCurrentLocation(null);
        lpn.setStatus(lpn.isPermanent() ? ContainerStatus.AVAILABLE : ContainerStatus.RETIRED);
    }

    private PutawayTask active(UUID userId, UUID taskId) {
        PutawayTask task = tasks.findLocked(taskId).orElseThrow(() -> ApiException.notFound("Put-away task not found"));
        if (task.getStatus() != PutawayTaskStatus.IN_PROGRESS || !task.isAssignedTo(userId)) {
            throw ApiException.conflict("You are no longer putting " + task.getContainer().getLpnId() + " away.", Map.of());
        }
        return task;
    }

    private Map<UUID, TaskView> views(Collection<PutawayTask> list) {
        Map<Container, Load> loads = director.loads(list.stream().filter(t -> NOT_DONE.contains(t.getStatus()))
                .map(PutawayTask::getContainer).toList());
        Map<UUID, TaskView> views = new HashMap<>();
        for (PutawayTask t : list) {
            List<LineView> lines = NOT_DONE.contains(t.getStatus())
                    ? loads.get(t.getContainer()).stock().stream().map(LineView::of).toList()
                    : t.getLines().stream().map(LineView::of).toList();
            views.put(t.getId(), TaskView.of(t, lines));
        }
        return views;
    }

    private static ApiException invalid(String field, String message) {
        return ApiException.invalid(message, Map.of(field, message));
    }
}
