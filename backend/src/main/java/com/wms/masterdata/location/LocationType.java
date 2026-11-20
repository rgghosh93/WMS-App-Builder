package com.wms.masterdata.location;

import static com.wms.masterdata.location.LocationIdentifier.AISLE;
import static com.wms.masterdata.location.LocationIdentifier.AREA;
import static com.wms.masterdata.location.LocationIdentifier.BAY;
import static com.wms.masterdata.location.LocationIdentifier.LEVEL;
import static com.wms.masterdata.location.LocationIdentifier.POSITION;
import static com.wms.masterdata.location.LocationIdentifier.ZONE;

import java.util.List;

/**
 * Kinds of location. Each type declares which identifiers make up its
 * unique address; more types (staging, dock, pack station...) will follow.
 */
public enum LocationType {
    STORAGE("Storage", List.of(AREA, ZONE, AISLE, BAY, LEVEL, POSITION));

    private final String label;
    private final List<LocationIdentifier> requiredIdentifiers;

    LocationType(String label, List<LocationIdentifier> requiredIdentifiers) {
        this.label = label;
        this.requiredIdentifiers = requiredIdentifiers;
    }

    public String label() {
        return label;
    }

    public List<LocationIdentifier> requiredIdentifiers() {
        return requiredIdentifiers;
    }
}
