package com.wms.putaway;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** Moving one received LPN into storage. Where to is decided when someone starts it. */
@Entity
@Table(name = "putaway_tasks")
public class PutawayTask extends FacilityRecord {

    @Id
    private UUID id;

    @Column(name = "task_number", nullable = false, length = 20)
    private String taskNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private PutawayTaskStatus status;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "container_id", nullable = false)
    private Container container;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "from_location_id", nullable = false)
    private Location fromLocation;

    /** Where it should go. Space there is kept for it while the task is in progress. Null if nowhere was found. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "directed_location_id")
    private Location directedLocation;

    @Column(name = "rule_name", length = 120)
    private String ruleName;

    @Enumerated(EnumType.STRING)
    @Column(length = 12)
    private PutawayHandling handling;

    /** Where it went. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "to_location_id")
    private Location toLocation;

    @Enumerated(EnumType.STRING)
    @Column(name = "override_reason", length = 20)
    private OverrideReason overrideReason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_to")
    private AppUser assignedTo;

    @OneToMany(mappedBy = "task", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("skippedAt ASC")
    private List<PutawaySkip> skips = new ArrayList<>();

    @OneToMany(mappedBy = "task", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PutawayTaskLine> lines = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Version
    private long version;

    protected PutawayTask() {
        // for JPA
    }

    PutawayTask(UUID id, String taskNumber, Container container, Location fromLocation) {
        this.id = id;
        this.taskNumber = taskNumber;
        this.container = container;
        this.fromLocation = fromLocation;
        this.status = PutawayTaskStatus.OPEN;
        this.createdAt = Instant.now();
    }

    void waitAt(Location location) {
        fromLocation = location;
    }

    void start(AppUser user) {
        status = PutawayTaskStatus.IN_PROGRESS;
        assignedTo = user;
        startedAt = Instant.now();
    }

    void assignTo(AppUser user) {
        assignedTo = user;
    }

    void direct(Location location, String rule, PutawayHandling how) {
        directedLocation = location;
        ruleName = rule;
        handling = how;
    }

    void skip(OverrideReason reason, AppUser user) {
        skips.add(new PutawaySkip(this, directedLocation, reason, user));
    }

    void release() {
        status = PutawayTaskStatus.OPEN;
        assignedTo = null;
        startedAt = null;
        directedLocation = null;
        ruleName = null;
        handling = null;
    }

    void complete(Location to, PutawayHandling how, OverrideReason reason) {
        status = PutawayTaskStatus.COMPLETED;
        toLocation = to;
        handling = how;
        overrideReason = reason;
        completedAt = Instant.now();
    }

    void cancel() {
        status = PutawayTaskStatus.CANCELLED;
        completedAt = Instant.now();
    }

    boolean isAssignedTo(UUID userId) {
        return assignedTo != null && assignedTo.getId().equals(userId);
    }

    public UUID getId() { return id; }
    public String getTaskNumber() { return taskNumber; }
    public PutawayTaskStatus getStatus() { return status; }
    public Container getContainer() { return container; }
    public Location getFromLocation() { return fromLocation; }
    public Location getDirectedLocation() { return directedLocation; }
    public String getRuleName() { return ruleName; }
    public PutawayHandling getHandling() { return handling; }
    public Location getToLocation() { return toLocation; }
    public OverrideReason getOverrideReason() { return overrideReason; }
    public AppUser getAssignedTo() { return assignedTo; }
    public List<PutawaySkip> getSkips() { return skips; }
    public List<PutawayTaskLine> getLines() { return lines; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }
}
