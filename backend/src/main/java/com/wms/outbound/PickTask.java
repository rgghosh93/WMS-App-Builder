package com.wms.outbound;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.wms.masterdata.container.Container;
import com.wms.masterdata.location.Location;
import com.wms.users.AppUser;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * A pick task: picking work created by one allocation run. Each of its details picks one
 * allocation (an item and quantity from one location or LPN) for one order line. A discrete
 * task picks for one order; a batch task picks for several orders in one walk.
 */
@Entity
@Table(name = "pick_tasks")
public class PickTask {

    @Id
    private UUID id;

    @Column(name = "task_number", nullable = false, length = 20)
    private String taskNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "pick_method", nullable = false, length = 16)
    private PickMethod pickMethod;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PickTaskStatus status;

    /** The picker working on it. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_to")
    private AppUser assignedTo;

    /** The outbound container (tote, carton...) it is picked into. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "to_container_id")
    private Container toContainer;

    /** The pack station the container was dropped at. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "drop_location_id")
    private Location dropLocation;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "picked_at")
    private Instant pickedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @OneToMany(mappedBy = "task", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("detailNumber ASC")
    private List<PickTaskDetail> details = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected PickTask() {
        // for JPA
    }

    PickTask(UUID id, String taskNumber, PickMethod pickMethod) {
        this.id = id;
        this.taskNumber = taskNumber;
        this.pickMethod = pickMethod;
        this.status = PickTaskStatus.OPEN;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    void assignNumber(String taskNumber) {
        this.taskNumber = taskNumber;
    }

    /** Adds a detail that picks this allocation. */
    PickTaskDetail addDetail(Allocation allocation, Location from, Container fromContainer) {
        PickTaskDetail detail = new PickTaskDetail(UUID.randomUUID(), this, details.size() + 1, allocation, from, fromContainer);
        details.add(detail);
        return detail;
    }

    /**
     * Cancels the open details that pick for this order. Details for other orders in a batch
     * stay open. Once none are open the task is cancelled, or ready to drop if it has picked stock.
     *
     * @return how many details were cancelled
     */
    int cancelFor(OutboundOrder order) {
        List<PickTaskDetail> cancelled = details.stream()
                .filter(d -> d.getStatus() == PickDetailStatus.OPEN && d.getOrder().getId().equals(order.getId()))
                .toList();
        cancelled.forEach(PickTaskDetail::cancel);
        if (!cancelled.isEmpty() && noneOpen()) {
            if (anyPicked()) {
                finishPicking();
            } else {
                status = PickTaskStatus.CANCELLED;
            }
        }
        return cancelled.size();
    }

    boolean noneOpen() {
        return details.stream().noneMatch(d -> d.getStatus() == PickDetailStatus.OPEN);
    }

    boolean anyPicked() {
        return details.stream().anyMatch(d -> d.getPickedQuantity() > 0);
    }

    boolean anyPickedFor(OutboundOrder order) {
        return details.stream().anyMatch(d -> d.getPickedQuantity() > 0 && d.getOrder().getId().equals(order.getId()));
    }

    /** Everything is picked; the container is ready to drop. */
    void finishPicking() {
        status = PickTaskStatus.PICKED;
        pickedAt = Instant.now();
    }

    /** Done. No drop location means nothing could be picked. */
    void complete(Location dropLocation) {
        this.dropLocation = dropLocation;
        status = PickTaskStatus.COMPLETED;
        completedAt = Instant.now();
    }

    /** Back to waiting for a picker. */
    void unassign() {
        assignedTo = null;
        startedAt = null;
        status = PickTaskStatus.OPEN;
    }

    void clearToContainer() {
        toContainer = null;
    }

    public int pickedQuantity() {
        return details.stream().mapToInt(PickTaskDetail::getPickedQuantity).sum();
    }

    /** The orders this task picks for, in detail order. */
    public List<OutboundOrder> orders() {
        Map<UUID, OutboundOrder> orders = new LinkedHashMap<>();
        details.forEach(d -> orders.putIfAbsent(d.getOrder().getId(), d.getOrder()));
        return List.copyOf(orders.values());
    }

    public int totalQuantity() {
        return details.stream().mapToInt(PickTaskDetail::getQuantity).sum();
    }

    public UUID getId() { return id; }
    public String getTaskNumber() { return taskNumber; }
    public PickMethod getPickMethod() { return pickMethod; }
    public PickTaskStatus getStatus() { return status; }
    public AppUser getAssignedTo() { return assignedTo; }
    public Container getToContainer() { return toContainer; }
    public Location getDropLocation() { return dropLocation; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getPickedAt() { return pickedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public List<PickTaskDetail> getDetails() { return details; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
}
