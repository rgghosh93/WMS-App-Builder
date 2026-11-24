package com.wms.builder;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A change someone asked the App Builder for, and where it has got to (AR-4). Its conversation is in {@link ChangeEntry};
 * its code is on a branch of its own. While the agent works on it, the agent's thread is the only one changing it but
 * for a request to stop, which is why the flags are changed by single updates rather than versioned.
 */
@Entity
@Table(name = "builder_changes")
public class ChangeRequest {

    public enum Status {
        /** Being worked out and built. */
        DRAFTING,
        /** Put into the environment changes are tried out in (wms-builder), not released yet. */
        ACCEPTED,
        IN_REVIEW,
        RELEASED,
        DISCARDED
    }

    /** What it needs from the person who asked for it. */
    public enum Attention {
        /** The agent has finished, and says what it did. */
        FINISHED,
        /** The agent is waiting for answers to its questions. */
        QUESTIONS,
        /** The agent stopped without finishing. */
        PROBLEM
    }

    @Id
    private UUID id;

    @Column(nullable = false, length = 20, updatable = false)
    private String number;

    @Column(nullable = false, length = 200)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(nullable = false)
    private boolean working;

    @Column(name = "stop_requested", nullable = false)
    private boolean stopRequested;

    @Column(nullable = false, length = 100, updatable = false)
    private String branch;

    @Column(name = "requested_by", nullable = false, length = 40, updatable = false)
    private String requestedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "input_tokens", nullable = false)
    private long inputTokens;

    @Column(name = "output_tokens", nullable = false)
    private long outputTokens;

    /** Why the agent's last run didn't finish, in words for the person; none if it did. */
    @Column(name = "last_problem", length = 1000)
    private String lastProblem;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "accepted_by", length = 40)
    private String acceptedBy;

    /** The change's own commit that was accepted. */
    @Column(name = "accepted_commit", length = 40)
    private String acceptedCommit;

    @Column(name = "discarded_at")
    private Instant discardedAt;

    @Column(name = "discarded_by", length = 40)
    private String discardedBy;

    @Column(name = "discard_reason", length = 500)
    private String discardReason;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Attention attention;

    @Column(name = "attention_at")
    private Instant attentionAt;

    protected ChangeRequest() {
        // for JPA
    }

    ChangeRequest(long sequence, String title, String requestedBy, Instant now) {
        this.id = UUID.randomUUID();
        this.number = "CR-%06d".formatted(sequence);
        this.title = title;
        this.status = Status.DRAFTING;
        this.branch = "change/" + number.toLowerCase();
        this.requestedBy = requestedBy;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public String getNumber() { return number; }
    public String getTitle() { return title; }
    public Status getStatus() { return status; }
    public boolean isWorking() { return working; }
    public boolean isStopRequested() { return stopRequested; }
    public String getBranch() { return branch; }
    public String getRequestedBy() { return requestedBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getInputTokens() { return inputTokens; }
    public long getOutputTokens() { return outputTokens; }
    public String getLastProblem() { return lastProblem; }
    public Instant getAcceptedAt() { return acceptedAt; }
    public String getAcceptedBy() { return acceptedBy; }
    public String getAcceptedCommit() { return acceptedCommit; }
    public Instant getDiscardedAt() { return discardedAt; }
    public String getDiscardedBy() { return discardedBy; }
    public String getDiscardReason() { return discardReason; }
    public Attention getAttention() { return attention; }
    public Instant getAttentionAt() { return attentionAt; }
}
