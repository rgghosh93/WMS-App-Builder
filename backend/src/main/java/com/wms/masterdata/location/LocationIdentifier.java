package com.wms.masterdata.location;

/** The parts that, combined, identify a location within its type. */
public enum LocationIdentifier {
    AREA("area", "Area"),
    ZONE("zone", "Zone"),
    AISLE("aisle", "Aisle"),
    BAY("bay", "Bay"),
    LEVEL("level", "Level"),
    POSITION("position", "Position");

    private final String field;
    private final String label;

    LocationIdentifier(String field, String label) {
        this.field = field;
        this.label = label;
    }

    /** Name of the matching field in API requests and responses. */
    public String field() {
        return field;
    }

    public String label() {
        return label;
    }
}
