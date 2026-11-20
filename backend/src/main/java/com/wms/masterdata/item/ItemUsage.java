package com.wms.masterdata.item;

import java.util.Optional;
import java.util.UUID;

/** Something that refers to items (inventory today). Blocks deleting an item in use. */
public interface ItemUsage {

    /** A short reason the item is in use, e.g. "120 EA on hand", or empty. */
    Optional<String> describeUse(UUID itemId);
}
