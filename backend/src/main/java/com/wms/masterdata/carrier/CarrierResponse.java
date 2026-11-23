package com.wms.masterdata.carrier;

import java.util.List;
import java.util.UUID;

import com.wms.common.ActiveStatus;

public record CarrierResponse(
        UUID id,
        String carrierCode,
        String name,
        CarrierMode mode,
        boolean trackingRequired,
        ActiveStatus status,
        List<Service> services,
        long version) {

    public record Service(UUID id, String serviceCode, String name, ActiveStatus status) {
    }

    public static CarrierResponse from(Carrier c) {
        return new CarrierResponse(c.getId(), c.getCarrierCode(), c.getName(), c.getMode(), c.isTrackingRequired(),
                c.getStatus(),
                c.getServices().stream()
                        .map(s -> new Service(s.getId(), s.getServiceCode(), s.getName(), s.getStatus())).toList(),
                c.getVersion());
    }
}
