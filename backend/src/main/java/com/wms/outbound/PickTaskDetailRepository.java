package com.wms.outbound;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PickTaskDetailRepository extends JpaRepository<PickTaskDetail, UUID> {

    /** The task detail made from each of these allocations, picked or still to pick. */
    @EntityGraph(attributePaths = {"task"})
    @Query("select d from PickTaskDetail d where d.allocation.id in :allocationIds or d.pickedAllocation.id in :allocationIds")
    List<PickTaskDetail> findByAllocations(Collection<UUID> allocationIds);

    Optional<PickTaskDetail> findFirstByAllocation_Id(UUID allocationId);
}
