package com.wms.masterdata.location;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wms.common.Codes;
import com.wms.common.api.ApiException;

@Service
@Transactional
public class LocationService {

    private final LocationRepository repository;
    private final List<LocationUsage> usages;

    public LocationService(LocationRepository repository, List<LocationUsage> usages) {
        this.repository = repository;
        this.usages = usages;
    }

    @Transactional(readOnly = true)
    public Page<Location> search(LocationSearch search, Pageable pageable) {
        return repository.findAll(search.toSpecification(), pageable);
    }

    @Transactional(readOnly = true)
    public Location get(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Location not found"));
    }

    public Location create(LocationRequest request) {
        Location location = new Location(UUID.randomUUID());
        apply(location, request);
        return repository.save(location);
    }

    public Location update(UUID id, LocationRequest request) {
        Location location = get(id);
        if (request.version() == null) {
            throw ApiException.invalid("Version is required when updating a location", Map.of("version", "required"));
        }
        if (request.version() != location.getVersion()) {
            throw ApiException.conflict("This location was changed by someone else. Reload it and try again.", Map.of());
        }
        apply(location, request);
        return repository.saveAndFlush(location);
    }

    public void delete(UUID id) {
        Location location = get(id);
        List<String> reasons = usages.stream().flatMap(u -> u.describeUse(id).stream()).toList();
        if (!reasons.isEmpty()) {
            throw ApiException.conflict("Location " + location.getLocationId() + " can't be deleted: "
                    + String.join("; ", reasons) + ". Move them first.", Map.of());
        }
        repository.delete(location);
    }

    /** Validates the request against the location type, normalizes codes, and copies it onto the entity. */
    private void apply(Location location, LocationRequest request) {
        LocationType type = request.type();
        Map<String, String> errors = new LinkedHashMap<>();

        Map<LocationIdentifier, String> identifiers = new LinkedHashMap<>();
        for (LocationIdentifier identifier : LocationIdentifier.values()) {
            String value = Codes.normalize(request.identifier(identifier));
            boolean required = type.requiredIdentifiers().contains(identifier);
            if (required && value == null) {
                errors.put(identifier.field(), "required for " + type.label() + " locations");
            } else if (!required && value != null) {
                errors.put(identifier.field(), "not used for " + type.label() + " locations");
            }
            identifiers.put(identifier, value);
        }
        if (request.maxWeight() != null && request.maxWeightUom() == null) {
            errors.put("maxWeightUom", "required when max weight is given");
        }
        if (request.maxVolume() != null && request.maxVolumeUom() == null) {
            errors.put("maxVolumeUom", "required when max volume is given");
        }
        if (!errors.isEmpty()) {
            throw ApiException.invalid("Some fields need attention", errors);
        }

        String locationId = Codes.normalize(request.locationId());
        UUID self = location.getId();

        repository.findByLocationId(locationId)
                .filter(other -> !other.getId().equals(self))
                .ifPresent(other -> errors.put("locationId", "already used by another location"));

        findByIdentifiers(type, identifiers)
                .filter(other -> !other.getId().equals(self))
                .ifPresent(other -> errors.put("identifiers",
                        "This " + describe(type) + " combination is already location " + other.getLocationId()));

        if (!errors.isEmpty()) {
            throw ApiException.conflict("A location with these details already exists", errors);
        }

        location.setLocationId(locationId);
        location.setType(type);
        location.setArea(identifiers.get(LocationIdentifier.AREA));
        location.setZone(identifiers.get(LocationIdentifier.ZONE));
        location.setAisle(identifiers.get(LocationIdentifier.AISLE));
        location.setBay(identifiers.get(LocationIdentifier.BAY));
        location.setLevel(identifiers.get(LocationIdentifier.LEVEL));
        location.setPosition(identifiers.get(LocationIdentifier.POSITION));
        location.setWidth(request.width());
        location.setDepth(request.depth());
        location.setHeight(request.height());
        location.setDimensionUom(request.dimensionUom());
        location.setMaxWeight(request.maxWeight());
        location.setMaxWeightUom(request.maxWeight() != null ? request.maxWeightUom() : null);
        location.setMaxVolume(request.maxVolume());
        location.setMaxVolumeUom(request.maxVolume() != null ? request.maxVolumeUom() : null);
        location.setMaxLpns(request.maxLpns());
        location.setMaxSkus(request.maxSkus());
        location.setMaxUnits(request.maxUnits());
    }

    private Optional<Location> findByIdentifiers(LocationType type, Map<LocationIdentifier, String> ids) {
        return repository.findByTypeAndAreaAndZoneAndAisleAndBayAndLevelAndPosition(
                type,
                ids.get(LocationIdentifier.AREA), ids.get(LocationIdentifier.ZONE),
                ids.get(LocationIdentifier.AISLE), ids.get(LocationIdentifier.BAY),
                ids.get(LocationIdentifier.LEVEL), ids.get(LocationIdentifier.POSITION));
    }

    private static String describe(LocationType type) {
        return String.join("/", type.requiredIdentifiers().stream().map(LocationIdentifier::label).toList());
    }
}
