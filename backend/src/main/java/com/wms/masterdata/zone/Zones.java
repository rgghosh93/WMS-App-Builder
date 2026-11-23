package com.wms.masterdata.zone;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.wms.common.ActiveStatus;
import com.wms.common.api.ApiException;
import com.wms.masterdata.location.Location;

/** Looking zones up, for what uses them. Only active zones count. */
@Component
public class Zones {

    private final ZoneRepository zones;
    private final ZoneLocationRepository members;

    Zones(ZoneRepository zones, ZoneLocationRepository members) {
        this.zones = zones;
        this.members = members;
    }

    /** The zone each of these locations is in, for a function; locations in none are left out. */
    public Map<UUID, Zone> of(ZoneFunction function, Collection<UUID> locationIds) {
        Map<UUID, Zone> result = new HashMap<>();
        if (!locationIds.isEmpty()) {
            members.findActive(function, locationIds).forEach(m -> result.put(m.getLocation().getId(), m.getZone()));
        }
        return result;
    }

    /** A zone's storage locations, in walking order; none if it is inactive. */
    public List<Location> locations(Zone zone) {
        return zone.getStatus() == ActiveStatus.ACTIVE ? members.findStorage(zone.getId()) : List.of();
    }

    public List<Zone> active(ZoneFunction function) {
        return zones.findByFunctionAndStatusOrderByCodeAsc(function, ActiveStatus.ACTIVE);
    }

    /** @param field to say which field is wrong */
    public Zone require(UUID id, ZoneFunction function, String field) {
        Zone zone = id == null ? null : zones.findById(id).orElse(null);
        if (zone == null || zone.getFunction() != function) {
            throw ApiException.invalid("Choose a zone", Map.of(field, "choose a " + function.label().toLowerCase() + " zone"));
        }
        return zone;
    }
}
