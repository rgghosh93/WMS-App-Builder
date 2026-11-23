package com.wms.control;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ReasonCodeRepository extends JpaRepository<ReasonCode, UUID> {

    List<ReasonCode> findAllByOrderByKindAscNameAsc();

    Optional<ReasonCode> findByKindAndCode(ReasonCode.Kind kind, String code);
}
