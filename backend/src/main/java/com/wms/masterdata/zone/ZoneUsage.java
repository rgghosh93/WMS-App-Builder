package com.wms.masterdata.zone;

import java.util.Optional;
import java.util.UUID;

/** Something that refers to zones. A zone can't be deleted while any usage reports it. */
public interface ZoneUsage {

    /** A short reason the zone is in use, or empty. */
    Optional<String> describeUse(UUID zoneId);
}
