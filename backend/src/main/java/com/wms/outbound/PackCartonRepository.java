package com.wms.outbound;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PackCartonRepository extends JpaRepository<PackCarton, UUID> {

    @Query("select c from PackCarton c where c.container.lpnId = :lpnId")
    Optional<PackCarton> findByLpn(String lpnId);

    /** The order a container is a carton of, unless it has shipped or was called off. */
    @Query("select o.orderNumber from PackCarton c join c.order o left join c.shipment s where c.container.id = :containerId "
            + "and c.status <> com.wms.outbound.CartonStatus.CANCELLED "
            + "and (s is null or s.status <> com.wms.outbound.ShipmentStatus.SHIPPED)")
    List<String> findLiveOrders(UUID containerId);

    Optional<PackCarton> findByTrackingNumber(String trackingNumber);
}
