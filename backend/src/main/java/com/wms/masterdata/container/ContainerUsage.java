package com.wms.masterdata.container;

import java.util.Optional;
import java.util.UUID;

/** Something that refers to containers (inventory today). Blocks deleting a container in use. */
public interface ContainerUsage {

    /** A short reason the container is in use, e.g. "it holds 3 inventory records", or empty. */
    Optional<String> describeUse(UUID containerId);
}
