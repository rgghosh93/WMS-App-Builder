package com.wms.replenishment;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.wms.facility.FacilityRecord;
import com.wms.inventory.InventoryRecord;
import com.wms.masterdata.item.Item;
import com.wms.masterdata.location.Location;
import com.wms.users.AppUser;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** Moving some of one inventory record to a pick location. Until it is moved, that much is set aside for it. */
@Entity
@Table(name = "replenishment_tasks")
public class ReplenishmentTask extends FacilityRecord {

    public enum Status { OPEN, IN_PROGRESS, COMPLETED, CANCELLED }

    /** Why, most urgent first. */
    public enum Reason { ORDERS_WAITING, EMPTY, BELOW_MINIMUM }

    /** How much more urgent than usual for replenishment. */
    static int adjust(Reason reason) {
        return switch (reason) {
            case ORDERS_WAITING -> -2;
            case EMPTY -> -1;
            case BELOW_MINIMUM -> 0;
        };
    }

    static final List<Status> ACTIVE = List.of(Status.OPEN, Status.IN_PROGRESS);

    @Id
    private UUID id;

    @Column(name = "task_number", nullable = false, length = 20)
    private String taskNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Status status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Reason reason;

    /** Lower is done first. */
    @Column(nullable = false)
    private int priority;

    @Column(name = "rule_id")
    private UUID ruleId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "item_id", nullable = false)
    private Item item;

    /** The inventory record to take from. It may be gone once the task is over. */
    @Column(name = "inventory_id", nullable = false)
    private UUID inventoryId;

    @Column(name = "lot_number", length = 40)
    private String lotNumber;

    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "from_location_id", nullable = false)
    private Location fromLocation;

    /** The LPN it is directly in, if any. */
    @Column(name = "from_lpn", length = 40)
    private String fromLpn;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "to_location_id", nullable = false)
    private Location toLocation;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "moved_quantity", nullable = false)
    private int movedQuantity;

    @Column(name = "short_quantity", nullable = false)
    private int shortQuantity;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_to")
    private AppUser assignedTo;

    /** Why it was cancelled. */
    @Column(length = 400)
    private String note;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Version
    private long version;

    protected ReplenishmentTask() {
        // for JPA
    }

    ReplenishmentTask(String taskNumber, ReplenishmentRule rule, Reason reason, InventoryRecord from, Location fromLocation, int quantity) {
        this.id = UUID.randomUUID();
        this.taskNumber = taskNumber;
        this.status = Status.OPEN;
        this.reason = reason;
        this.priority = reason.ordinal() + 1;
        this.ruleId = rule.getId();
        this.item = rule.getItem();
        this.inventoryId = from.getId();
        this.lotNumber = from.getLotNumber();
        this.expiryDate = from.getExpiryDate();
        this.fromLocation = fromLocation;
        this.fromLpn = from.getContainer() == null ? null : from.getContainer().getLpnId();
        this.toLocation = rule.getLocation();
        this.quantity = quantity;
        this.createdAt = Instant.now();
    }

    int remaining() {
        return quantity - movedQuantity - shortQuantity;
    }

    void start(AppUser user) {
        status = Status.IN_PROGRESS;
        assignedTo = user;
        startedAt = Instant.now();
    }

    /** Back to waiting, for whoever comes. */
    void release() {
        status = Status.OPEN;
        assignedTo = null;
        startedAt = null;
    }

    void moved(int eaches) {
        movedQuantity += eaches;
        finishIfDone();
    }

    /** The rest isn't there. */
    void closeShort() {
        shortQuantity += remaining();
        finishIfDone();
    }

    private void finishIfDone() {
        if (remaining() == 0) {
            status = Status.COMPLETED;
            endedAt = Instant.now();
        }
    }

    void cancel(String why) {
        status = Status.CANCELLED;
        note = why == null || why.length() <= 400 ? why : why.substring(0, 400);
        endedAt = Instant.now();
    }

    boolean isAssignedTo(UUID userId) {
        return assignedTo != null && assignedTo.getId().equals(userId);
    }

    public UUID getId() { return id; }
    public String getTaskNumber() { return taskNumber; }
    public Status getStatus() { return status; }
    public Reason getReason() { return reason; }
    public int getPriority() { return priority; }
    public UUID getRuleId() { return ruleId; }
    public Item getItem() { return item; }
    public UUID getInventoryId() { return inventoryId; }
    public String getLotNumber() { return lotNumber; }
    public LocalDate getExpiryDate() { return expiryDate; }
    public Location getFromLocation() { return fromLocation; }
    public String getFromLpn() { return fromLpn; }
    public Location getToLocation() { return toLocation; }
    public int getQuantity() { return quantity; }
    public int getMovedQuantity() { return movedQuantity; }
    public int getShortQuantity() { return shortQuantity; }
    public AppUser getAssignedTo() { return assignedTo; }
    public String getNote() { return note; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getEndedAt() { return endedAt; }
}
