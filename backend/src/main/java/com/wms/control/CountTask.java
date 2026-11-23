package com.wms.control;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.wms.facility.FacilityRecord;
import com.wms.masterdata.location.Location;

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

/** Counting everything at one location. The location is locked from when counting starts until it is settled. */
@Entity
@Table(name = "count_tasks")
public class CountTask extends FacilityRecord {

    public enum Status {
        /** Planned. */
        OPEN,
        /** Someone is counting. */
        COUNTING,
        /** Too different to settle itself: approve it or have it counted again. */
        REVIEW,
        /** To be counted again. */
        RECOUNT,
        COMPLETED,
        CANCELLED
    }

    static final List<Status> LOCKING = List.of(Status.COUNTING, Status.REVIEW, Status.RECOUNT);
    static final List<Status> ACTIVE = List.of(Status.OPEN, Status.COUNTING, Status.REVIEW, Status.RECOUNT);

    @Id
    private UUID id;

    @Column(nullable = false, length = 20)
    private String number;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "location_id", nullable = false)
    private Location location;

    @Column(name = "planned_by", nullable = false, length = 120)
    private String plannedBy;

    @Column(name = "planned_at", nullable = false)
    private Instant plannedAt;

    /** Who is counting, or counted last. */
    @Column(name = "counter_id")
    private UUID counterId;

    @Column(length = 120)
    private String counter;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "counted_at")
    private Instant countedAt;

    @Column(length = 120)
    private String decider;

    @Column(name = "decided_at")
    private Instant decidedAt;

    /** How many times it has been counted. */
    @Column(nullable = false)
    private int rounds;

    @OneToMany(mappedBy = "task", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("countedAt ASC")
    private List<CountEntry> entries = new ArrayList<>();

    @OneToMany(mappedBy = "task", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<CountResult> results = new ArrayList<>();

    @Version
    private long version;

    protected CountTask() {
        // for JPA
    }

    CountTask(String number, Location location, Actor planner) {
        this.id = UUID.randomUUID();
        this.number = number;
        this.status = Status.OPEN;
        this.location = location;
        this.plannedBy = planner.name();
        this.plannedAt = Instant.now();
    }

    void start(Actor by) {
        status = Status.COUNTING;
        assign(by);
        startedAt = Instant.now();
    }

    void assign(Actor by) {
        counterId = by.id();
        counter = by.name();
    }

    /** Not counted after all; what was counted so far is thrown away. */
    void giveBack() {
        entries.clear();
        status = rounds == 0 ? Status.OPEN : Status.RECOUNT;
        if (rounds == 0) {
            counterId = null;
            counter = null;
            startedAt = null;
        }
    }

    void counted(Status as) {
        status = as;
        rounds++;
        countedAt = Instant.now();
    }

    void recount(Actor by) {
        status = Status.RECOUNT;
        entries.clear();
        decide(by);
    }

    void complete(Actor by) {
        status = Status.COMPLETED;
        if (by != null) {
            decide(by);
        }
    }

    void cancel(Actor by) {
        status = Status.CANCELLED;
        decide(by);
    }

    private void decide(Actor by) {
        decider = by.name();
        decidedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getNumber() { return number; }
    public Status getStatus() { return status; }
    public Location getLocation() { return location; }
    public String getPlannedBy() { return plannedBy; }
    public Instant getPlannedAt() { return plannedAt; }
    public UUID getCounterId() { return counterId; }
    public String getCounter() { return counter; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCountedAt() { return countedAt; }
    public String getDecider() { return decider; }
    public Instant getDecidedAt() { return decidedAt; }
    public int getRounds() { return rounds; }
    public List<CountEntry> getEntries() { return entries; }
    public List<CountResult> getResults() { return results; }
}
