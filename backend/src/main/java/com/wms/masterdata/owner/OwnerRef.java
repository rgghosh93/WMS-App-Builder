package com.wms.masterdata.owner;

import java.util.UUID;

public record OwnerRef(UUID id, String code, String name) {

    public static OwnerRef of(Owner o) {
        return o == null ? null : new OwnerRef(o.getId(), o.getCode(), o.getName());
    }
}
