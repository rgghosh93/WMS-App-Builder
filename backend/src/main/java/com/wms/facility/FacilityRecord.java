package com.wms.facility;

import java.util.UUID;

import org.hibernate.annotations.TenantId;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;

/**
 * Something that belongs to one facility. It is given the facility it is made in, and Hibernate leaves everything
 * of other facilities out of every query and lookup.
 */
@MappedSuperclass
public abstract class FacilityRecord {

    @TenantId
    @Column(name = "facility_id", nullable = false, updatable = false)
    private UUID facilityId;

    public UUID getFacilityId() { return facilityId; }
}
