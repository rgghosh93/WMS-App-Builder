package com.wms.masterdata.uom;

import java.util.Optional;
import java.util.UUID;

/** Something that refers to units of measure (item packaging today). Blocks deleting a unit in use. */
public interface UomUsage {

    /** A short reason the unit is in use, e.g. "used by 12 items", or empty. */
    Optional<String> describeUse(UUID uomId);
}
