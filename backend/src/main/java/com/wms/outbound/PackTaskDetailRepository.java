package com.wms.outbound;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PackTaskDetailRepository extends JpaRepository<PackTaskDetail, UUID> {

    /** Which pick each allocation came from, for stock being packed: rows of (allocation id, pick task detail). */
    @Query("select a.id, d.pickDetail from PackTaskDetail d join d.allocation a where a.id in :allocationIds")
    List<Object[]> findPicksOfUnpacked(Collection<UUID> allocationIds);

    @Query("select a.id, d.pickDetail from PackCartonLine l join l.allocation a join l.detail d where a.id in :allocationIds")
    List<Object[]> findPicksOfPacked(Collection<UUID> allocationIds);
}
