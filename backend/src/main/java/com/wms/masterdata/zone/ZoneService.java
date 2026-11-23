package com.wms.masterdata.zone;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wms.common.ActiveStatus;
import com.wms.common.Codes;
import com.wms.common.api.ApiException;
import com.wms.masterdata.location.Location;
import com.wms.masterdata.location.LocationRepository;
import com.wms.masterdata.location.LocationType;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Zones and the locations in them. */
@Service
@Transactional
public class ZoneService {

    private final ZoneRepository zones;
    private final ZoneLocationRepository members;
    private final LocationRepository locations;
    private final List<ZoneUsage> usages;

    ZoneService(ZoneRepository zones, ZoneLocationRepository members, LocationRepository locations, List<ZoneUsage> usages) {
        this.zones = zones;
        this.members = members;
        this.locations = locations;
        this.usages = usages;
    }

    public record FunctionView(ZoneFunction function, String label, String description) {
    }

    public record ZoneView(UUID id, ZoneFunction function, String code, String name, ActiveStatus status, Integer priority,
                           boolean allocatable, long locationCount, long version) {
    }

    /** @param priority    for allocation zones: lower is used first
     *  @param allocatable for allocation zones: whether orders get stock from it at all */
    public record ZoneRequest(
            @NotNull ZoneFunction function,
            @NotBlank @Size(max = 40) @Pattern(regexp = Codes.PATTERN, message = Codes.MESSAGE) String code,
            @NotBlank @Size(max = 120) String name,
            @NotNull ActiveStatus status,
            @Min(1) @Max(999) Integer priority,
            Boolean allocatable,
            Long version) {
    }

    /**
     * Which storage locations: by ID, or by address. Address parts left out match anything; ranges
     * include both ends.
     */
    public record Filter(List<String> locationIds, String area, String zone, String aisleFrom, String aisleTo, String bayFrom,
                         String bayTo, String level) {
    }

    /** @param move take locations from other zones of the same function */
    public record AddRequest(@NotNull Filter filter, Boolean move) {
    }

    /**
     * @param matching  storage locations the filter finds
     * @param already   in this zone already
     * @param elsewhere in another zone of this function, by its code
     */
    public record Preview(int matching, int already, Map<String, Integer> elsewhere) {
    }

    public record ZoneOfLocation(ZoneFunction function, String label, UUID zoneId, String code, String name, ActiveStatus status) {
    }

    public List<FunctionView> functions() {
        return Arrays.stream(ZoneFunction.values()).map(f -> new FunctionView(f, f.label(), f.description())).toList();
    }

    @Transactional(readOnly = true)
    public List<ZoneView> list(ZoneFunction function) {
        Map<UUID, Long> counts = new HashMap<>();
        members.countByZone().forEach(row -> counts.put((UUID) row[0], (Long) row[1]));
        return zones.findAllByOrderByFunctionAscCodeAsc().stream().filter(z -> function == null || z.getFunction() == function)
                .map(z -> view(z, counts.getOrDefault(z.getId(), 0L))).toList();
    }

    @Transactional(readOnly = true)
    public ZoneView get(UUID id) {
        return view(load(id));
    }

    public ZoneView create(ZoneRequest request) {
        Zone zone = new Zone(UUID.randomUUID(), request.function());
        apply(zone, request);
        return view(zones.saveAndFlush(zone));
    }

    public ZoneView update(UUID id, ZoneRequest request) {
        Zone zone = load(id);
        if (request.version() == null) {
            throw ApiException.invalid("Version is required when updating a zone", Map.of("version", "required"));
        }
        if (request.version() != zone.getVersion()) {
            throw ApiException.conflict("This zone was changed by someone else. Reload it and try again.", Map.of());
        }
        if (request.function() != zone.getFunction()) {
            throw ApiException.invalid("Some fields need attention", Map.of("function", "can't be changed"));
        }
        apply(zone, request);
        return view(zones.saveAndFlush(zone));
    }

    public void delete(UUID id) {
        Zone zone = load(id);
        List<String> reasons = usages.stream().map(u -> u.describeUse(id)).flatMap(Optional::stream).toList();
        if (!reasons.isEmpty()) {
            throw ApiException.conflict(zone.getCode() + " can't be deleted: " + String.join("; ", reasons) + ".", Map.of());
        }
        members.removeAll(id);
        zones.deleteById(id);
    }

    private void apply(Zone zone, ZoneRequest request) {
        String code = Codes.normalize(request.code());
        zones.findByFunctionAndCode(request.function(), code).filter(other -> !other.getId().equals(zone.getId())).ifPresent(other -> {
            throw ApiException.conflict("A zone with this code already exists",
                    Map.of("code", "already used by another " + request.function().label().toLowerCase(Locale.ROOT) + " zone"));
        });
        boolean allocation = request.function() == ZoneFunction.ALLOCATION;
        zone.setCode(code);
        zone.setName(request.name().trim());
        zone.setStatus(request.status());
        zone.setPriority(allocation ? request.priority() : null);
        zone.setAllocatable(!allocation || !Boolean.FALSE.equals(request.allocatable()));
    }

    // ---------- Locations ----------

    @Transactional(readOnly = true)
    public Page<Location> locations(UUID id, String q, Pageable page) {
        load(id);
        String like = q == null || q.isBlank() ? "%" : "%" + q.trim().toLowerCase(Locale.ROOT).replace("\\", "\\\\")
                .replace("%", "\\%").replace("_", "\\_") + "%";
        return members.search(id, like, page);
    }

    @Transactional(readOnly = true)
    public List<ZoneOfLocation> ofLocation(UUID locationId) {
        return members.findByLocation_IdOrderByFunctionAsc(locationId).stream().map(ZoneLocation::getZone)
                .map(z -> new ZoneOfLocation(z.getFunction(), z.getFunction().label(), z.getId(), z.getCode(), z.getName(), z.getStatus())).toList();
    }

    @Transactional(readOnly = true)
    public Preview preview(UUID id, Filter filter) {
        Zone zone = load(id);
        return preview(zone, find(filter));
    }

    /** Puts locations in a zone. Those in another zone of the function are only moved if asked. */
    public Preview add(UUID id, AddRequest request) {
        Zone zone = zones.findLocked(id).orElseThrow(() -> ApiException.notFound("Zone not found"));
        List<Location> found = find(request.filter());
        Preview before = preview(zone, found);
        if (found.isEmpty()) {
            throw ApiException.invalid("No storage locations match", Map.of("filter", "no storage locations match"));
        }
        if (!before.elsewhere().isEmpty() && !Boolean.TRUE.equals(request.move())) {
            String list = String.join(", ", before.elsewhere().entrySet().stream().map(e -> e.getValue() + " in " + e.getKey()).toList());
            throw ApiException.conflict("Some are in another " + zone.getFunction().label().toLowerCase(Locale.ROOT) + " zone: " + list + ".",
                    Map.of("elsewhere", list));
        }
        Map<UUID, ZoneLocation> has = memberships(zone, found);
        List<ZoneLocation> added = new ArrayList<>();
        for (Location location : found) {
            ZoneLocation member = has.get(location.getId());
            if (member == null) {
                added.add(new ZoneLocation(zone, location));
            } else {
                member.moveTo(zone);
            }
        }
        members.saveAll(added);
        members.flush();
        return before;
    }

    public ZoneView remove(UUID id, List<UUID> locationIds) {
        zones.findLocked(id).orElseThrow(() -> ApiException.notFound("Zone not found"));
        if (locationIds == null || locationIds.isEmpty()) {
            throw ApiException.invalid("Choose locations to remove", Map.of("locationIds", "choose locations to remove"));
        }
        members.remove(id, locationIds);
        return get(id);
    }

    private Preview preview(Zone zone, List<Location> found) {
        int already = 0;
        Map<String, Integer> elsewhere = new TreeMap<>();
        for (ZoneLocation member : memberships(zone, found).values()) {
            if (member.getZone().getId().equals(zone.getId())) {
                already++;
            } else {
                elsewhere.merge(member.getZone().getCode(), 1, Integer::sum);
            }
        }
        return new Preview(found.size(), already, elsewhere);
    }

    private Map<UUID, ZoneLocation> memberships(Zone zone, Collection<Location> found) {
        Map<UUID, ZoneLocation> result = new LinkedHashMap<>();
        if (!found.isEmpty()) {
            members.findByFunctionAndLocation_IdIn(zone.getFunction(), found.stream().map(Location::getId).toList())
                    .forEach(m -> result.put(m.getLocation().getId(), m));
        }
        return result;
    }

    private List<Location> find(Filter f) {
        List<String> ids = f.locationIds() == null ? List.of() : f.locationIds().stream().map(Codes::normalize).filter(i -> i != null).distinct().toList();
        if (!ids.isEmpty()) {
            List<Location> found = new ArrayList<>();
            for (String id : ids) {
                Location l = locations.findByLocationId(id).orElse(null);
                if (l == null || l.getType() != LocationType.STORAGE) {
                    throw ApiException.invalid(id + (l == null ? " is not a known location" : " is not a storage location"),
                            Map.of("locationIds", id + (l == null ? " not found" : " is not for storage")));
                }
                found.add(l);
            }
            return found;
        }
        String area = Codes.normalize(f.area());
        String zone = Codes.normalize(f.zone());
        if (area == null) {
            throw ApiException.invalid("Say which locations", Map.of("area", "enter the area"));
        }
        return locations.findByTypeOrderByLocationId(LocationType.STORAGE).stream()
                .filter(l -> area.equals(l.getArea()) && (zone == null || zone.equals(l.getZone()))
                        && within(l.getAisle(), f.aisleFrom(), f.aisleTo()) && within(l.getBay(), f.bayFrom(), f.bayTo())
                        && within(l.getLevel(), f.level(), f.level()))
                .toList();
    }

    private static boolean within(String value, String from, String to) {
        String low = Codes.normalize(from);
        String high = Codes.normalize(to);
        if (low == null && high == null) {
            return true;
        }
        return value != null && (low == null || value.compareTo(low) >= 0) && (high == null || value.compareTo(high) <= 0);
    }

    private Zone load(UUID id) {
        return zones.findById(id).orElseThrow(() -> ApiException.notFound("Zone not found"));
    }

    private ZoneView view(Zone z) {
        return view(z, members.countByZone_Id(z.getId()));
    }

    private static ZoneView view(Zone z, long count) {
        return new ZoneView(z.getId(), z.getFunction(), z.getCode(), z.getName(), z.getStatus(), z.getPriority(), z.isAllocatable(), count,
                z.getVersion());
    }
}
