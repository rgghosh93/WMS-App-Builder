package com.wms.putaway;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.wms.inventory.InventoryRecord;
import com.wms.inventory.InventoryStatus;
import com.wms.masterdata.container.Container;
import com.wms.masterdata.container.ContainerCategory;
import com.wms.masterdata.container.ContainerType;
import com.wms.masterdata.item.Item;

/**
 * An LPN to put away and everything in it.
 *
 * @param nested containers inside it
 */
record Load(Container lpn, List<Container> nested, List<InventoryRecord> stock) {

    /** The same stock, as far as storing it together goes. */
    record Kind(UUID itemId, String lot, InventoryStatus status) {
        static Kind of(InventoryRecord r) {
            return new Kind(r.getItem().getId(), r.getLotNumber(), r.getStatus());
        }
    }

    Set<Kind> kinds() {
        return stock.stream().map(Kind::of).collect(Collectors.toSet());
    }

    Set<UUID> itemIds() {
        return stock.stream().map(r -> r.getItem().getId()).collect(Collectors.toSet());
    }

    int eaches() {
        return stock.stream().mapToInt(InventoryRecord::getQuantity).sum();
    }

    /** In pounds. Items without a weight count for nothing. */
    double weight(PutawayHandling handling) {
        double weight = stock.stream().mapToDouble(r -> weight(r.getItem()) * r.getQuantity()).sum();
        if (handling == PutawayHandling.KEEP_LPN) {
            for (Container c : all()) {
                ContainerType t = c.getContainerType();
                if (t.getTareWeight() != null) {
                    weight += t.getTareWeightUom().toPounds(t.getTareWeight().doubleValue());
                }
            }
        }
        return weight;
    }

    /** In cubic inches: what is in it, or the whole box if that is bigger. */
    double volume(PutawayHandling handling) {
        double contents = stock.stream().mapToDouble(r -> volume(r.getItem()) * r.getQuantity()).sum();
        return handling == PutawayHandling.KEEP_LPN && isBox(lpn.getContainerType())
                ? Math.max(contents, outside(lpn.getContainerType())) : contents;
    }

    List<Container> all() {
        List<Container> all = new ArrayList<>(nested);
        all.add(lpn);
        return all;
    }

    static double weight(Item i) {
        return i.getWeight() == null ? 0 : i.getWeightUom().toPounds(i.getWeight().doubleValue());
    }

    static double volume(Item i) {
        return i.getWidth() == null || i.getDepth() == null || i.getHeight() == null ? 0
                : i.getDimensionUom().toInches(i.getWidth().doubleValue()) * i.getDimensionUom().toInches(i.getDepth().doubleValue())
                        * i.getDimensionUom().toInches(i.getHeight().doubleValue());
    }

    /** Takes up its own outside size however full it is. A pallet is as big as what is on it. */
    static boolean isBox(ContainerType t) {
        return t.getCategory() != ContainerCategory.PALLET;
    }

    static double outside(ContainerType t) {
        return Objects.requireNonNull(t.getDimensionUom()).toInches(t.getWidth().doubleValue())
                * t.getDimensionUom().toInches(t.getDepth().doubleValue()) * t.getDimensionUom().toInches(t.getHeight().doubleValue());
    }
}
