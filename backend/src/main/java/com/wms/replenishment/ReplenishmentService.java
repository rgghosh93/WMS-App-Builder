package com.wms.replenishment;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wms.common.Codes;
import com.wms.common.api.ApiException;
import com.wms.inventory.InventoryRecord;
import com.wms.inventory.InventoryRepository;
import com.wms.inventory.InventoryStatus;
import com.wms.inventory.LocationLocks;
import com.wms.inventory.LooseStock;
import com.wms.inventory.LpnLifecycle;
import com.wms.inventory.PlannedMoves;
import com.wms.inventory.StockLedger;
import com.wms.inventory.StockLedger.Note;
import com.wms.inventory.StockLedger.Place;
import com.wms.inventory.StockTransaction.Type;
import com.wms.masterdata.container.Container;
import com.wms.masterdata.item.ItemScan;
import com.wms.masterdata.location.Location;
import com.wms.putaway.StorageSpace;
import com.wms.replenishment.ReplenishmentTask.Reason;
import com.wms.replenishment.ReplenishmentTask.Status;
import com.wms.replenishment.ReplenishmentViews.DeviceState;
import com.wms.replenishment.ReplenishmentViews.TaskView;
import com.wms.users.AppUserRepository;
import com.wms.users.UserWork;
import com.wms.work.Directions;
import com.wms.work.Directions.Candidate;
import com.wms.work.WorkKind;

/**
 * Doing replenishment tasks. Everything is scanned on the device first, and the stock moves in one go
 * when it is put in the pick location. Stock is locked before its tasks, here as everywhere.
 */
@Service
@Transactional
public class ReplenishmentService implements PlannedMoves {

    private static final int SHOW_WAITING = 20;
    private static final Limit LOOK_THROUGH = Limit.of(200);

    private final ReplenishmentTaskRepository tasks;
    private final InventoryRepository inventory;
    private final AppUserRepository users;
    private final StorageSpace space;
    private final LocationLocks locks;
    private final LooseStock loose;
    private final LpnLifecycle lpns;
    private final StockLedger ledger;
    private final Directions directions;

    ReplenishmentService(ReplenishmentTaskRepository tasks, InventoryRepository inventory, AppUserRepository users, StorageSpace space,
                         LocationLocks locks, LooseStock loose, LpnLifecycle lpns, StockLedger ledger,
                         Directions directions) {
        this.tasks = tasks;
        this.inventory = inventory;
        this.users = users;
        this.space = space;
        this.locks = locks;
        this.loose = loose;
        this.lpns = lpns;
        this.ledger = ledger;
        this.directions = directions;
    }

    /**
     * @param movedSoFar what the device showed as already moved, so sending it again moves nothing more
     * @param quantity   in eaches, put in the pick location now
     * @param shortage   the rest isn't there
     * @param allowOver  put it there although there is no room by the books
     */
    public record Confirm(Integer movedSoFar, String fromLocationId, String lpnId, String itemBarcode, Integer quantity, Boolean shortage,
                          String toLocationId, Boolean allowOver) {
    }

    // ---------- Looking ----------

    @Transactional(readOnly = true)
    public Page<TaskView> search(String q, Status status, Pageable page) {
        String like = q == null || q.isBlank() ? "%" : "%" + q.trim().toLowerCase(Locale.ROOT).replace("\\", "\\\\")
                .replace("%", "\\%").replace("_", "\\_") + "%";
        return tasks.search(like, status, page).map(TaskView::of);
    }

    @Transactional(readOnly = true)
    public DeviceState state(UUID userId) {
        ReplenishmentTask mine = tasks.findFirstByAssignedTo_IdAndStatus(userId, Status.IN_PROGRESS).orElse(null);
        List<ReplenishmentTask> open = tasks.findByStatusInOrderByPriorityAscCreatedAtAscTaskNumberAsc(List.of(Status.OPEN));
        List<UUID> forMe = directions.arrange(userId, open.stream().map(t -> new Candidate(t.getId(), own(t.getReason()))).toList());
        if (mine != null) {
            return new DeviceState(TaskView.of(mine, ItemScan.of(mine.getItem())), forMe.size(), List.of());
        }
        Map<UUID, ReplenishmentTask> byId = new HashMap<>();
        open.forEach(t -> byId.put(t.getId(), t));
        return new DeviceState(null, forMe.size(), forMe.stream().limit(SHOW_WAITING).map(id -> TaskView.of(byId.get(id))).toList());
    }

    // ---------- On the desktop ----------

    public TaskView cancel(String who, UUID id) {
        ReplenishmentTask task = tasks.findLocked(id).orElseThrow(() -> ApiException.notFound("Replenishment task not found"));
        if (!ReplenishmentTask.ACTIVE.contains(task.getStatus())) {
            throw ApiException.conflict(task.getTaskNumber() + " is already " + (task.getStatus() == Status.CANCELLED ? "cancelled." : "done."), Map.of());
        }
        task.cancel("Cancelled by " + who);
        tasks.flush();
        return TaskView.of(task);
    }

    // ---------- On a device ----------

    /** @param taskId the task to take; the most urgent one that can be done now if null */
    public DeviceState start(UUID userId, UUID taskId) {
        ReplenishmentTask mine = tasks.findFirstByAssignedTo_IdAndStatus(userId, Status.IN_PROGRESS).orElse(null);
        if (mine != null) {
            return state(userId);
        }
        ReplenishmentTask task = taskId == null ? next(userId) : lockOpen(taskId);
        if (task == null) {
            throw ApiException.conflict(taskId == null ? "No replenishment is waiting." : "Someone else has that task, or it was called off.", Map.of());
        }
        if (taskId != null) {
            String number = task.getTaskNumber();
            directions.assignedToOther(taskId, userId).ifPresent(other -> {
                throw ApiException.conflict(number + " is assigned to " + other + ".", Map.of());
            });
        }
        if (!usable(task)) {
            throw ApiException.conflict("The stock of " + task.getTaskNumber() + " can't be used any more.", Map.of());
        }
        directions.starting(userId);
        task.start(users.getReferenceById(userId));
        tasks.flush(); // from here on, a count can't start there
        for (Location l : List.of(task.getFromLocation(), task.getToLocation())) {
            if (locks.isLocked(l.getId())) {
                throw ApiException.conflict(l.getLocationId() + " is being counted.", Map.of());
            }
        }
        return state(userId);
    }

    private int own(Reason reason) {
        return directions.own(WorkKind.REPLENISHMENT, ReplenishmentTask.adjust(reason));
    }

    private ReplenishmentTask next(UUID userId) {
        Map<UUID, List<UUID>> places = new LinkedHashMap<>();
        List<Candidate> waiting = new ArrayList<>();
        for (Object[] t : tasks.findWaiting(LocalDate.now(), LOOK_THROUGH)) {
            places.put((UUID) t[0], List.of((UUID) t[1], (UUID) t[2]));
            waiting.add(new Candidate((UUID) t[0], own((Reason) t[3])));
        }
        for (UUID id : directions.arrange(userId, waiting)) {
            if (locks.locked(places.get(id)).isEmpty()) {
                ReplenishmentTask locked = lockOpen(id);
                if (locked != null) {
                    return locked;
                }
            }
        }
        return null;
    }

    private boolean usable(ReplenishmentTask task) {
        return inventory.findById(task.getInventoryId()).filter(InventoryRecord::isUsable).isPresent();
    }

    private ReplenishmentTask lockOpen(UUID id) {
        return tasks.findLocked(id).filter(t -> t.getStatus() == Status.OPEN).orElse(null);
    }

    /** Gives a task back, for whoever comes. What was moved already stays moved. */
    public DeviceState release(UUID userId, UUID taskId) {
        ReplenishmentTask task = active(userId, taskId);
        if (usable(task)) {
            task.release();
        } else {
            task.cancel("The stock can't be used any more");
        }
        tasks.flush();
        return state(userId);
    }

    /** Moves stock to the pick location. @return the item moved, to follow up on, or null if there is nothing to follow up */
    public UUID confirm(UUID userId, UUID taskId, Confirm request) {
        space.lock();
        UUID inventoryId = tasks.findInventoryId(taskId).orElseThrow(() -> ApiException.notFound("Replenishment task not found"));
        InventoryRecord from = inventory.findLocked(List.of(inventoryId)).stream().findFirst().orElse(null);
        ReplenishmentTask task = tasks.findLocked(taskId).orElseThrow();
        int quantity = request.quantity() == null ? 0 : request.quantity();
        boolean shortage = Boolean.TRUE.equals(request.shortage());
        if (request.movedSoFar() == null) {
            throw invalid("movedSoFar", "Say how much was moved before");
        }
        if (task.isAssignedTo(userId) && request.movedSoFar() != task.getMovedQuantity()
                && request.movedSoFar() + quantity == task.getMovedQuantity() && quantity > 0) {
            return null; // sent twice
        }
        if (task.getStatus() == Status.COMPLETED && task.isAssignedTo(userId) && quantity == 0 && shortage && task.getShortQuantity() > 0) {
            return null; // sent twice
        }
        task = active(userId, taskId);
        if (request.movedSoFar() != task.getMovedQuantity()) {
            throw ApiException.conflict("This task has changed. Carry on with what is shown now.", Map.of());
        }
        Location source = task.getFromLocation();
        Location to = task.getToLocation();
        if (from == null || StockLedger.locationOf(from) == null || !StockLedger.locationOf(from).getId().equals(source.getId())) {
            throw ApiException.conflict("That stock is no longer at " + source.getLocationId() + ". Give the task back and cancel it.", Map.of());
        }
        check(task, request, from);
        if (quantity < 0 || quantity == 0 && !shortage) {
            throw invalid("quantity", "Enter the quantity");
        }
        if (quantity > task.remaining()) {
            throw invalid("quantity", "Only " + task.remaining() + " EA are to be moved");
        }
        Container lpn = from.getContainer();
        if (quantity > 0) {
            if (!from.isUsable()) {
                throw ApiException.conflict((from.isExpired() ? "This stock has expired. " : "This stock is on hold. ")
                        + "Leave it and give the task back.", Map.of());
            }
            if (!to.getLocationId().equals(Codes.normalize(request.toLocationId()))) {
                throw invalid("toLocationId", request.toLocationId() == null || request.toLocationId().isBlank() ? "Scan the pick location"
                        : "Wrong location. Put it in " + to.getLocationId());
            }
            InventoryRecord moving = from.copy(quantity);
            StorageSpace.Check room = space.forLoose(moving, to);
            if (room.refused() != null) {
                throw invalid("toLocationId", room.refused());
            }
            if (room.over() != null && !Boolean.TRUE.equals(request.allowOver())) {
                throw ApiException.conflict(room.over() + ".", Map.of("capacity", room.over()));
            }
            ledger.log(Type.REPLENISH, from, quantity, Place.of(from), Place.at(to, null), Note.by(userId, task.getTaskNumber()));
        }
        int missing = shortage ? task.remaining() - quantity : 0;
        // What is missing is held before the rest moves, while the record is where it was.
        if (missing > 0) {
            hold(from, missing, task, userId);
        }
        if (quantity > 0) {
            loose.put(from, quantity, to);
            task.moved(quantity);
        }
        if (missing > 0) {
            task.closeShort();
        }
        tasks.flush();
        if (lpn != null) {
            lpns.retireIfEmptied(lpn);
        }
        return task.getItem().getId();
    }

    private void check(ReplenishmentTask task, Confirm request, InventoryRecord from) {
        Location source = task.getFromLocation();
        if (!source.getLocationId().equals(Codes.normalize(request.fromLocationId()))) {
            throw invalid("fromLocationId", "Wrong location. Go to " + source.getLocationId());
        }
        for (Location l : List.of(source, task.getToLocation())) {
            if (locks.isLocked(l.getId())) {
                throw ApiException.conflict(l.getLocationId() + " is being counted.", Map.of());
            }
        }
        if (from.getContainer() != null && !from.getContainer().getLpnId().equals(Codes.normalize(request.lpnId()))) {
            throw invalid("lpnId", "Wrong LPN. Take it from " + from.getContainer().getLpnId());
        }
        String scanned = request.itemBarcode() == null ? "" : request.itemBarcode().trim();
        boolean right = ItemScan.of(task.getItem()).stream().anyMatch(s -> s.value().equalsIgnoreCase(scanned));
        if (!right && !(Boolean.TRUE.equals(request.shortage()) && Integer.valueOf(0).equals(request.quantity()))) {
            throw invalid("itemBarcode", "Wrong item. Take " + task.getItem().getItemId());
        }
    }

    /** Stock that should be there and isn't goes on hold, to be looked into. */
    private void hold(InventoryRecord from, int missing, ReplenishmentTask task, UUID userId) {
        if (from.getStatus() != InventoryStatus.AVAILABLE) {
            return;
        }
        String why = "Short replenishment";
        ledger.log(Type.HOLD, from, missing, Place.of(from), Place.of(from), InventoryStatus.AVAILABLE, InventoryStatus.ON_HOLD,
                Note.by(userId, task.getTaskNumber()).because(why));
        String reason = why + ", " + task.getTaskNumber();
        if (missing == from.getQuantity()) {
            from.hold(InventoryStatus.ON_HOLD, reason);
            return;
        }
        from.setQuantity(from.getQuantity() - missing);
        InventoryRecord held = from.copy(missing);
        held.hold(InventoryStatus.ON_HOLD, reason);
        inventory.save(held);
    }

    private ReplenishmentTask active(UUID userId, UUID taskId) {
        ReplenishmentTask task = tasks.findLocked(taskId).orElseThrow(() -> ApiException.notFound("Replenishment task not found"));
        if (task.getStatus() != Status.IN_PROGRESS || !task.isAssignedTo(userId)) {
            throw ApiException.conflict("You are no longer doing " + task.getTaskNumber() + ".", Map.of());
        }
        return task;
    }

    // ---------- For whatever else uses stock ----------

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Integer> allocatedByInventory(Collection<UUID> inventoryIds) {
        Map<UUID, Integer> result = new HashMap<>();
        if (!inventoryIds.isEmpty()) {
            tasks.sumByInventory(inventoryIds).forEach(row -> result.put((UUID) row[0], ((Number) row[1]).intValue()));
        }
        return result;
    }

    @Override
    public void callOff(Collection<UUID> inventoryIds, String why) {
        if (!inventoryIds.isEmpty()) {
            tasks.findOpenFrom(inventoryIds).forEach(t -> t.cancel(why));
            tasks.flush();
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> beingMoved(UUID locationId) {
        return tasks.findUnderWayAt(locationId).stream().findFirst().map(n -> n + " is moving stock there");
    }

    private static ApiException invalid(String field, String message) {
        return ApiException.invalid(message, Map.of(field, message));
    }

    /** What someone is replenishing. */
    @Component
    static class InHand implements UserWork {
        private final ReplenishmentTaskRepository tasks;

        InHand(ReplenishmentTaskRepository tasks) {
            this.tasks = tasks;
        }

        @Override
        public List<String> inHand(UUID userId) {
            return tasks.findFirstByAssignedTo_IdAndStatus(userId, Status.IN_PROGRESS)
                    .map(t -> List.of("Replenishing " + t.getTaskNumber())).orElse(List.of());
        }
    }
}
