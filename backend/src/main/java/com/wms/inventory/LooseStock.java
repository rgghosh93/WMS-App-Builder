package com.wms.inventory;

import java.util.List;

import org.springframework.stereotype.Component;

import com.wms.masterdata.location.Location;

/** Putting stock loose in a location. */
@Component
public class LooseStock {

    private final InventoryRepository inventory;

    LooseStock(InventoryRepository inventory) {
        this.inventory = inventory;
    }

    /** Puts some or all of a record loose in a location, adding to the same stock there. The rest stays where it is. */
    public void put(InventoryRecord r, int quantity, Location to) {
        boolean all = quantity == r.getQuantity();
        InventoryRecord same = inventory.findByLocation_IdIn(List.of(to.getId())).stream().filter(r::mergesWith).findFirst().orElse(null);
        if (same != null) {
            same.setQuantity(same.getQuantity() + quantity);
            if (all) {
                inventory.delete(r);
            }
        } else if (all) {
            r.placeIn(to);
        } else {
            InventoryRecord part = r.copy(quantity);
            part.placeIn(to);
            inventory.save(part);
        }
        if (!all) {
            r.setQuantity(r.getQuantity() - quantity);
        }
    }
}
