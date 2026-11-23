package com.wms.masterdata.zone;

import java.util.UUID;

import com.wms.facility.FacilityRecord;
import com.wms.masterdata.location.Location;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** A location in a zone. The function is repeated so a location can't be in two zones of one. */
@Entity
@Table(name = "zone_locations")
public class ZoneLocation extends FacilityRecord {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "zone_id", nullable = false)
    private Zone zone;

    @Enumerated(EnumType.STRING)
    @Column(name = "zone_function", nullable = false, length = 16)
    private ZoneFunction function;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "location_id", nullable = false)
    private Location location;

    protected ZoneLocation() {
        // for JPA
    }

    ZoneLocation(Zone zone, Location location) {
        this.id = UUID.randomUUID();
        this.zone = zone;
        this.function = zone.getFunction();
        this.location = location;
    }

    void moveTo(Zone zone) {
        this.zone = zone;
    }

    public Zone getZone() { return zone; }
    public Location getLocation() { return location; }
}
