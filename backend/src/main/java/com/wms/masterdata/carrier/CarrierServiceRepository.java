package com.wms.masterdata.carrier;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CarrierServiceRepository extends JpaRepository<CarrierService, UUID> {
}
