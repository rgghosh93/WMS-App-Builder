package com.wms.navigation;

/**
 * One UI page in the WMS, as listed on the home screen and in the sidebar directory.
 *
 * @param id          stable identifier, unique across the catalog
 * @param title       display name
 * @param description one-line summary of what the page is for
 * @param route       frontend route the page lives at
 * @param status      whether the page can be opened yet
 */
public record PageDefinition(
        String id,
        String title,
        String description,
        String route,
        PageStatus status) {
}
