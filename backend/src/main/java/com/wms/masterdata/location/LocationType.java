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
 * unique address; more types (staging...) will follow.
 */
public enum LocationType {
    STORAGE("Storage", List.of(AREA, ZONE, AISLE, BAY, LEVEL, POSITION)),
    /** Where goods wait between being received and being put away. Stock here isn't allocated. */
    RECEIVING("Receiving", List.of(AREA, ZONE, POSITION)),
    /** A pack station: where picked containers are dropped to be packed. */
    PACKING("Packing", List.of(AREA, ZONE, POSITION)),
    /** A dock door: where deliveries are received and shipments are loaded. Stock here isn't allocated. */
    DOCK("Dock door", List.of(AREA, ZONE, POSITION));

    private final String label;
    private final List<LocationIdentifier> requiredIdentifiers;

    LocationType(String label, List<LocationIdentifier> requiredIdentifiers) {
        this.label = label;
        this.requiredIdentifiers = requiredIdentifiers;
    }

    /** Whether deliveries are received here, and wait here to be put away. */
    public boolean isForReceiving() {
        return this == RECEIVING || this == DOCK;
    }

    public String label() {
        return label;
    }

    public List<LocationIdentifier> requiredIdentifiers() {
        return requiredIdentifiers;
    }
}
