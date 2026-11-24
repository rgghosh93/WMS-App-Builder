package com.wms.builder;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One turn of a change request's conversation, kept as it was said so it can be shown and carried on (AR-3). */
@Entity
@Table(name = "builder_entries")
public class ChangeEntry {

    public enum Role {
        /** The person asked or answered. */
        PERSON,
        /** The agent said something or used tools. */
        AGENT,
        /** What came of the tools it used. */
        RESULTS
    }

    @Id
    private UUID id;

    @Column(name = "change_id", nullable = false, updatable = false)
    private UUID changeId;

    @Column(nullable = false, updatable = false)
    private int seq;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private Role role;

    /** The turn's blocks as JSON. */
    @Column(nullable = false, updatable = false, columnDefinition = "TEXT")
    private String blocks;

    @Column(length = 80, updatable = false)
    private String model;

    @Column(name = "input_tokens", updatable = false)
    private Long inputTokens;

    @Column(name = "output_tokens", updatable = false)
    private Long outputTokens;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ChangeEntry() {
        // for JPA
    }

    ChangeEntry(UUID changeId, int seq, Role role, String blocks, String model, Long inputTokens, Long outputTokens, Instant now) {
        this.id = UUID.randomUUID();
        this.changeId = changeId;
        this.seq = seq;
        this.role = role;
        this.blocks = blocks;
        this.model = model;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.createdAt = now;
    }

    public UUID getId() { return id; }
    public UUID getChangeId() { return changeId; }
    public int getSeq() { return seq; }
    public Role getRole() { return role; }
    public String getBlocks() { return blocks; }
    public String getModel() { return model; }
    public Long getInputTokens() { return inputTokens; }
    public Long getOutputTokens() { return outputTokens; }
    public Instant getCreatedAt() { return createdAt; }
}
