package com.wms.masterdata.carrier;

import java.util.Optional;
import java.util.UUID;

/** Something that refers to carriers and their services (shipments, manifests). Blocks deleting one in use. */
public interface CarrierUsage {

    /** A short reason the carrier is in use, e.g. "it has 12 shipments", or empty. */
    Optional<String> describeUse(UUID carrierId);

    /** Whether a service is in use. */
    boolean usesService(UUID serviceId);
}
