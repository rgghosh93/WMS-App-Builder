package com.wms.builder;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Where change requests' conversations are kept. */
interface ChangeEntryRepository extends JpaRepository<ChangeEntry, UUID> {

    List<ChangeEntry> findByChangeIdOrderBySeq(UUID changeId);

    @Query("select coalesce(max(e.seq), 0) from ChangeEntry e where e.changeId = :changeId")
    int lastSeq(@Param("changeId") UUID changeId);
}
