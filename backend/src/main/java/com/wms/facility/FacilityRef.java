package com.wms.facility;

import java.util.UUID;

public record FacilityRef(UUID id, String code, String name) {

    public static FacilityRef of(Facility f) {
        return new FacilityRef(f.getId(), f.getCode(), f.getName());
    }
}
