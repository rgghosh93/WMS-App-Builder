package com.wms.masterdata.container;

import java.util.Optional;

import org.springframework.stereotype.Component;

import com.wms.common.Codes;
import com.wms.masterdata.item.ItemRepository;
import com.wms.masterdata.item.ItemUomRepository;
import com.wms.masterdata.location.LocationRepository;

/** Catches labels scanned by mistake before they become containers. */
@Component
public class NewLpnCheck {

    private final LocationRepository locations;
    private final ItemRepository items;
    private final ItemUomRepository itemUoms;

    public NewLpnCheck(LocationRepository locations, ItemRepository items, ItemUomRepository itemUoms) {
        this.locations = locations;
        this.items = items;
        this.itemUoms = itemUoms;
    }

    /** Why a label not seen before can't be an LPN, or empty if it can. */
    public Optional<String> problemWith(String lpnId) {
        if (lpnId.length() > 40 || !lpnId.matches(Codes.PATTERN)) {
            return Optional.of("That isn't a valid LPN");
        }
        if (locations.findByLocationId(lpnId).isPresent()) {
            return Optional.of(lpnId + " is a location, not a container");
        }
        if (items.existsByItemId(lpnId) || items.existsByBarcode(lpnId) || itemUoms.existsByBarcode(lpnId)) {
            return Optional.of(lpnId + " is an item, not a container");
        }
        return Optional.empty();
    }
}
