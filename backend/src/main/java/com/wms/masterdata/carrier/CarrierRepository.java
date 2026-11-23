package com.wms.masterdata.carrier;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CarrierRepository extends JpaRepository<Carrier, UUID> {

    Optional<Carrier> findByCarrierCode(String carrierCode);
}
