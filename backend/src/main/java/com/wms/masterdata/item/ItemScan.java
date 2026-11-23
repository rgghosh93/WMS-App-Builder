package com.wms.masterdata.item;

import java.util.ArrayList;
import java.util.List;

/** Something printed on an item that identifies it, and the unit it stands for. */
public record ItemScan(String value, String uom, int eachesPerUnit) {

    /** Everything that can be scanned for an item: its barcode or ID as eaches, and each packaging level's barcode. */
    public static List<ItemScan> of(Item item) {
        List<ItemScan> scans = new ArrayList<>();
        if (item.getBarcode() != null) {
            scans.add(new ItemScan(item.getBarcode(), "EA", 1));
        }
        scans.add(new ItemScan(item.getItemId(), "EA", 1));
        for (ItemUom pack : item.getUoms()) {
            if (pack.getBarcode() != null) {
                scans.add(new ItemScan(pack.getBarcode(), pack.getUom().getUomCode(), pack.getQuantity()));
            }
        }
        return scans;
    }
}
