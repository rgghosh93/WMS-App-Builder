package com.wms.work;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface TaskDirectionRepository extends JpaRepository<TaskDirection, UUID> {

    @Query("select d from TaskDirection d left join fetch d.assignedTo where d.taskId in :ids")
    List<TaskDirection> findFor(Collection<UUID> ids);

    List<TaskDirection> findByAssignedTo_Id(UUID userId);

    List<TaskDirection> findByClaimedBy(UUID userId);

    /** Leaves the version alone, so this never gets in the way of someone directing the task. */
    @Modifying(flushAutomatically = true)
    @Query("update TaskDirection d set d.claimedBy = null, d.claimedAt = null where d.claimedBy = :userId")
    void unclaim(UUID userId);
}
