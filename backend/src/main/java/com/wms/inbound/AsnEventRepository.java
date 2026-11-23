package com.wms.inbound;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AsnEventRepository extends JpaRepository<AsnEvent, UUID> {

    List<AsnEvent> findByAsn_IdOrderByOccurredAtAsc(UUID asnId);
}
