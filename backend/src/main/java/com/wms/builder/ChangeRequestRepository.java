package com.wms.builder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Where change requests are kept. */
interface ChangeRequestRepository extends JpaRepository<ChangeRequest, UUID> {

    @Query(value = "select nextval('builder_change_seq')", nativeQuery = true)
    long nextNumber();

    List<ChangeRequest> findAllByOrderByUpdatedAtDesc();

    /** Starts the agent on it, unless it already is. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ChangeRequest c set c.working = true, c.stopRequested = false, c.lastProblem = null, c.attention = null, "
            + "c.attentionAt = null, c.updatedAt = :now "
            + "where c.id = :id and c.working = false and c.status <> com.wms.builder.ChangeRequest.Status.DISCARDED")
    int startWorking(@Param("id") UUID id, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ChangeRequest c set c.stopRequested = true where c.id = :id and c.working = true")
    int requestStop(@Param("id") UUID id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ChangeRequest c set c.inputTokens = c.inputTokens + :input, c.outputTokens = c.outputTokens + :output, "
            + "c.updatedAt = :now where c.id = :id")
    int addUsage(@Param("id") UUID id, @Param("input") long input, @Param("output") long output, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ChangeRequest c set c.working = false, c.stopRequested = false, c.lastProblem = :problem, "
            + "c.updatedAt = :now where c.id = :id")
    int stopWorking(@Param("id") UUID id, @Param("problem") String problem, @Param("now") Instant now);

    /** After a restart nothing is being worked on, whatever was said before. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ChangeRequest c set c.working = false, c.stopRequested = false, c.lastProblem = :problem, "
            + "c.attention = com.wms.builder.ChangeRequest.Attention.PROBLEM, c.attentionAt = :now "
            + "where c.working = true")
    int stopAllWork(@Param("problem") String problem, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ChangeRequest c set c.status = com.wms.builder.ChangeRequest.Status.ACCEPTED, c.acceptedAt = :now, "
            + "c.acceptedBy = :by, c.acceptedCommit = :commit, c.updatedAt = :now where c.id = :id")
    int accepted(@Param("id") UUID id, @Param("by") String by, @Param("commit") String commit, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ChangeRequest c set c.attention = :attention, c.attentionAt = :now where c.id = :id")
    int needsAttention(@Param("id") UUID id, @Param("attention") ChangeRequest.Attention attention, @Param("now") Instant now);

    /** The change requests someone asked for that need them, latest first. */
    List<ChangeRequest> findByRequestedByAndAttentionIsNotNullOrderByAttentionAtDesc(String requestedBy);

    /** Closes it; the agent stops after the step it is on, if it is working. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ChangeRequest c set c.status = com.wms.builder.ChangeRequest.Status.DISCARDED, c.discardedAt = :now, "
            + "c.discardedBy = :by, c.discardReason = :reason, c.stopRequested = true, c.updatedAt = :now "
            + "where c.id = :id and c.status <> com.wms.builder.ChangeRequest.Status.DISCARDED")
    int discard(@Param("id") UUID id, @Param("by") String by, @Param("reason") String reason, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ChangeRequest c set c.status = :status, c.discardedAt = null, c.discardedBy = null, c.discardReason = null, "
            + "c.updatedAt = :now where c.id = :id and c.status = com.wms.builder.ChangeRequest.Status.DISCARDED")
    int reopen(@Param("id") UUID id, @Param("status") ChangeRequest.Status status, @Param("now") Instant now);
}
