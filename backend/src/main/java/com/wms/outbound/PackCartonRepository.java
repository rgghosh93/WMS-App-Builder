package com.wms.outbound;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PackCartonRepository extends JpaRepository<PackCarton, UUID> {

    @Query("select c from PackCarton c where c.container.lpnId = :lpnId")
    Optional<PackCarton> findByLpn(String lpnId);

    Optional<PackCarton> findByTrackingNumber(String trackingNumber);
}
