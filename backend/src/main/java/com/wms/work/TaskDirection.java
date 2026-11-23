package com.wms.work;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.wms.common.ActiveStatus;
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

/** What was said about a task: its priority, who is to do it, and who was just offered it. */
@Entity
@Table(name = "task_directions")
public class TaskDirection {

    /** How long a task offered to someone isn't offered to anyone else. */
    static final Duration CLAIM = Duration.ofMinutes(10);

    @Id
    @Column(name = "task_id")
    private UUID taskId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private WorkKind kind;

    /** Instead of the task's own; null to leave it at that. */
    private Integer priority;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_to")
    private AppUser assignedTo;

    @Column(name = "claimed_by")
    private UUID claimedBy;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Version
    private Long version;

    protected TaskDirection() {
        // for JPA
    }

    TaskDirection(UUID taskId, WorkKind kind) {
        this.taskId = taskId;
        this.kind = kind;
    }

    void setPriority(Integer priority) {
        this.priority = priority;
    }

    void assign(AppUser user) {
        assignedTo = user;
        claimedBy = null;
        claimedAt = null;
    }

    void claim(UUID userId) {
        claimedBy = userId;
        claimedAt = Instant.now();
    }

    void unclaim() {
        claimedBy = null;
        claimedAt = null;
    }

    /** Who is to do it. Someone who can't sign in any more doesn't count. */
    AppUser assignee() {
        return assignedTo == null || assignedTo.getStatus() != ActiveStatus.ACTIVE ? null : assignedTo;
    }

    boolean isAssignedTo(UUID userId) {
        return assignee() != null && assignedTo.getId().equals(userId);
    }

    /** Whether someone else has been told to do it, or was just offered it. */
    boolean isForSomeoneElse(UUID userId) {
        if (assignee() != null) {
            return !isAssignedTo(userId);
        }
        return claimedBy != null && !claimedBy.equals(userId) && claimedAt.isAfter(Instant.now().minus(CLAIM));
    }

    public UUID getTaskId() { return taskId; }
    public WorkKind getKind() { return kind; }
    public Integer getPriority() { return priority; }
}
