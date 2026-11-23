package com.wms.putaway;

import java.time.Instant;
import java.util.UUID;

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

/** A location a task was sent to that couldn't be used. The task isn't sent there again. */
@Entity
@Table(name = "putaway_skips")
public class PutawaySkip {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_id", nullable = false)
    private PutawayTask task;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "location_id", nullable = false)
    private Location location;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OverrideReason reason;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "skipped_by", nullable = false)
    private AppUser skippedBy;

    @Column(name = "skipped_at", nullable = false)
    private Instant skippedAt;

    protected PutawaySkip() {
        // for JPA
    }

    PutawaySkip(PutawayTask task, Location location, OverrideReason reason, AppUser user) {
        this.id = UUID.randomUUID();
        this.task = task;
        this.location = location;
        this.reason = reason;
        this.skippedBy = user;
        this.skippedAt = Instant.now();
    }

    public Location getLocation() { return location; }
    public OverrideReason getReason() { return reason; }
    public AppUser getSkippedBy() { return skippedBy; }
    public Instant getSkippedAt() { return skippedAt; }
}
