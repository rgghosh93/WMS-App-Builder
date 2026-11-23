package com.wms.outbound;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.wms.facility.FacilityRecord;
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
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * Work at a pack station: pack what is in one outbound container into shipping cartons,
 * each for one order. Created when the container is dropped.
 */
@Entity
@Table(name = "pack_tasks")
public class PackTask extends FacilityRecord {

    @Id
    private UUID id;

    @Column(name = "task_number", nullable = false, length = 20)
    private String taskNumber;

    /** The pick task that filled the container. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pick_task_id", nullable = false)
    private PickTask pickTask;

    /** The container being packed out of. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "container_id", nullable = false)
    private Container container;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "station_id", nullable = false)
    private Location station;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PackTaskStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_to")
    private AppUser assignedTo;

    @OneToMany(mappedBy = "task", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("detailNumber ASC")
    private List<PackTaskDetail> details = new ArrayList<>();

    @OneToMany(mappedBy = "task", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("createdAt ASC")
    private List<PackCarton> cartons = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Version
    private long version;

    protected PackTask() {
        // for JPA
    }

    PackTask(UUID id, String taskNumber, PickTask pickTask, Container container, Location station) {
        this.id = id;
        this.taskNumber = taskNumber;
        this.pickTask = pickTask;
        this.container = container;
        this.station = station;
        this.status = PackTaskStatus.OPEN;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    void addDetail(PickTaskDetail pick) {
        details.add(new PackTaskDetail(UUID.randomUUID(), this, details.size() + 1, pick));
    }

    /** Marks the task changed, so two people changing it at once can't both succeed. */
    void touch() {
        updatedAt = Instant.now();
    }

    void start(AppUser user) {
        assignedTo = user;
        status = PackTaskStatus.IN_PROGRESS;
        if (startedAt == null) {
            startedAt = Instant.now();
        }
        touch();
    }

    void complete() {
        status = PackTaskStatus.COMPLETED;
        completedAt = Instant.now();
        touch();
    }

    /** Nothing in it is to be packed any more. */
    void cancel() {
        status = PackTaskStatus.CANCELLED;
        completedAt = Instant.now();
        touch();
    }

    /** Nothing is left to pack and no carton is open. */
    boolean isDone() {
        return details.stream().allMatch(d -> d.remaining() == 0)
                && cartons.stream().noneMatch(c -> c.getStatus() == CartonStatus.OPEN);
    }

    /** Whether it ended with nothing packed or missing: all of it was called off. */
    boolean isCalledOff() {
        return details.stream().allMatch(d -> d.getPackedQuantity() == 0 && d.getShortQuantity() == 0);
    }

    /** As urgent as the most urgent of its orders. */
    int priority() {
        return orders().stream().mapToInt(OutboundOrder::getPriority).min().orElse(9);
    }

    /** The orders in the container, in detail order. Those cancelled or undone are left out, unless that is all there is. */
    public List<OutboundOrder> orders() {
        Map<UUID, OutboundOrder> orders = new LinkedHashMap<>();
        details.stream().filter(d -> d.getCancelledQuantity() == 0).forEach(d -> orders.putIfAbsent(d.getOrder().getId(), d.getOrder()));
        if (orders.isEmpty()) {
            details.forEach(d -> orders.putIfAbsent(d.getOrder().getId(), d.getOrder()));
        }
        return List.copyOf(orders.values());
    }

    public UUID getId() { return id; }
    public String getTaskNumber() { return taskNumber; }
    public PickTask getPickTask() { return pickTask; }
    public Container getContainer() { return container; }
    public Location getStation() { return station; }
    public PackTaskStatus getStatus() { return status; }
    public AppUser getAssignedTo() { return assignedTo; }
    public List<PackTaskDetail> getDetails() { return details; }
    public List<PackCarton> getCartons() { return cartons; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }
}
