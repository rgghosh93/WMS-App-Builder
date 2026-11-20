package com.wms.outbound;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PickTaskDetailRepository extends JpaRepository<PickTaskDetail, UUID> {

    /** The task detail made from each of these allocations. */
    @EntityGraph(attributePaths = {"task"})
    List<PickTaskDetail> findByAllocation_IdIn(Collection<UUID> allocationIds);
}
