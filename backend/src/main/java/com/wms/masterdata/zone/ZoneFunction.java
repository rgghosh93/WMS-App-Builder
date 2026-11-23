package com.wms.masterdata.zone;

/** What a zone is for. A location is in at most one zone of each function. */
public enum ZoneFunction {
    ALLOCATION("Allocation", "Which stock orders get first, and which they never get"),
    PICKING("Picking", "Picks are split into one task per zone, and people can pick in one zone"),
    PUTAWAY("Put-away", "Put-away rules send stock to a zone"),
    COUNTING("Counting", "Counts can be planned for a zone"),
    REPLENISHMENT("Replenishment", "Replenishment rules bring stock from a zone");

    private final String label;
    private final String description;

    ZoneFunction(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String label() { return label; }
    public String description() { return description; }
}
