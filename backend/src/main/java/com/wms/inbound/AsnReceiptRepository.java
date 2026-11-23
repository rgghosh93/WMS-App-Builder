package com.wms.inbound;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AsnReceiptRepository extends JpaRepository<AsnReceipt, UUID> {

    @EntityGraph(attributePaths = {"line", "line.item", "container", "location", "receivedBy", "reversedBy"})
    List<AsnReceipt> findByAsn_IdOrderByReceivedAtDescRequestPartAsc(UUID asnId);

    boolean existsByRequestId(UUID requestId);

    boolean existsByAsn_IdAndContainer_Id(UUID asnId, UUID containerId);
}
