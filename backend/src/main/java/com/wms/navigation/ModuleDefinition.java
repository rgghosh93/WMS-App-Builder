package com.wms.navigation;

import java.util.List;

/**
 * A functional area of the WMS (e.g. Inbound, Inventory) and the pages it contains.
 *
 * @param id          stable identifier
 * @param name        display name
 * @param description one-line summary of the module
 * @param icon        icon key the frontend maps to an icon
 * @param pages       pages in display order
 */
public record ModuleDefinition(
        String id,
        String name,
        String description,
        String icon,
        List<PageDefinition> pages) {

    public ModuleDefinition {
        pages = pages == null ? List.of() : List.copyOf(pages);
    }
}
