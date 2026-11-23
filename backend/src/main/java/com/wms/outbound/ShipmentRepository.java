package com.wms.outbound;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

public interface ShipmentRepository extends JpaRepository<Shipment, UUID>, JpaSpecificationExecutor<Shipment> {

    @Override
    @EntityGraph(attributePaths = {"order", "carrierService", "carrierService.carrier", "manifest"})
    Page<Shipment> findAll(Specification<Shipment> spec, Pageable pageable);

    List<Shipment> findByOrder_IdOrderByShipmentNumber(UUID orderId);

    List<Shipment> findByManifest_IdOrderByShipmentNumber(UUID manifestId);

    /** Packed and not loaded, for one carrier. */
    @Query("select s from Shipment s where s.status = com.wms.outbound.ShipmentStatus.OPEN "
            + "and s.carrierService.carrier.id = :carrierId order by s.shipmentNumber")
    List<Shipment> findWaitingFor(UUID carrierId);

    long countByCarrierService_Carrier_Id(UUID carrierId);

    long countByCarrierService_Id(UUID serviceId);

    @Query(value = "select nextval('shipment_number_seq')", nativeQuery = true)
    long nextNumber();
}
