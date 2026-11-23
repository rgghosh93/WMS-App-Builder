package com.wms.masterdata.item;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.wms.common.Codes;
import com.wms.common.api.ApiException;

/** Finds items by what is typed or scanned. Several owners may have an item with the same ID or barcode. */
@Component
@Transactional(readOnly = true)
public class ItemFinder {

    private final ItemRepository items;
    private final ItemUomRepository packs;

    ItemFinder(ItemRepository items, ItemUomRepository packs) {
        this.items = items;
        this.packs = packs;
    }

    /**
     * @param owner the owner's code, if several have the item
     * @param field to say which field is wrong
     */
    public Item byId(String itemId, String owner, String field) {
        String code = Codes.normalize(itemId);
        if (code == null) {
            throw ApiException.invalid("Choose an item", Map.of(field, "choose an item"));
        }
        return one(items.findAllByItemId(code), code, owner, field);
    }

    /** An item by its ID, its barcode, or a barcode of its packaging. */
    public Item scanned(String scanned, String owner, String field) {
        String code = Codes.normalize(scanned);
        if (code == null) {
            throw ApiException.invalid("Scan an item", Map.of(field, "Scan an item"));
        }
        Set<Item> found = new LinkedHashSet<>(items.findAllByItemId(code));
        found.addAll(items.findAllByBarcode(code));
        packs.findAllByBarcode(code).forEach(p -> found.add(p.getItem()));
        return one(List.copyOf(found), code, owner, field);
    }

    private static Item one(List<Item> found, String code, String owner, String field) {
        String wanted = Codes.normalize(owner);
        List<Item> theirs = wanted == null ? found : found.stream().filter(i -> i.getOwner().getCode().equals(wanted)).toList();
        if (theirs.isEmpty()) {
            String message = code + " is not a known item" + (found.isEmpty() ? "" : " of " + wanted);
            throw ApiException.invalid(message, Map.of(field, message));
        }
        if (theirs.size() > 1) {
            String owners = theirs.stream().map(i -> i.getOwner().getCode()).distinct().sorted().collect(Collectors.joining(","));
            throw ApiException.conflict(code + " could be an item of " + owners.replace(",", ", ") + ". Say whose it is.", Map.of("owner", owners));
        }
        return theirs.getFirst();
    }
}
