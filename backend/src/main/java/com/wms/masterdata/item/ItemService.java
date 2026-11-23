package com.wms.masterdata.item;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wms.common.ActiveStatus;
import com.wms.common.Codes;
import com.wms.common.api.ApiException;
import com.wms.masterdata.owner.Owners;
import com.wms.masterdata.uom.UnitOfMeasure;
import com.wms.masterdata.uom.UnitOfMeasureRepository;

@Service
@Transactional
public class ItemService {

    private final ItemRepository repository;
    private final ItemUomRepository itemUomRepository;
    private final UnitOfMeasureRepository uomRepository;
    private final List<ItemUsage> usages;
    private final Owners owners;

    public ItemService(ItemRepository repository, ItemUomRepository itemUomRepository,
                       UnitOfMeasureRepository uomRepository, List<ItemUsage> usages, Owners owners) {
        this.repository = repository;
        this.itemUomRepository = itemUomRepository;
        this.uomRepository = uomRepository;
        this.usages = usages;
        this.owners = owners;
    }

    @Transactional(readOnly = true)
    public Page<ItemResponse> search(ItemSearch search, Pageable pageable) {
        return repository.findAll(search.toSpecification(), pageable).map(ItemResponse::from);
    }

    @Transactional(readOnly = true)
    public ItemResponse get(UUID id) {
        return ItemResponse.from(load(id));
    }

    public ItemResponse create(ItemRequest request) {
        Item item = new Item(UUID.randomUUID());
        item.setOwner(owners.forNew(request.owner()));
        apply(item, request, true);
        return ItemResponse.from(repository.save(item));
    }

    public ItemResponse update(UUID id, ItemRequest request) {
        Item item = load(id);
        if (request.version() == null) {
            throw ApiException.invalid("Version is required when updating an item", Map.of("version", "required"));
        }
        if (request.version() != item.getVersion()) {
            throw ApiException.conflict("This item was changed by someone else. Reload it and try again.", Map.of());
        }
        if (request.owner() != null && !request.owner().isBlank() && !owners.named(request.owner()).getId().equals(item.getOwnerId())) {
            throw ApiException.invalid("Some fields need attention", Map.of("owner", "can't be changed"));
        }
        apply(item, request, false);
        if (request.uoms() != null) {
            // Packaging lives in its own table; touch the item so a packs-only edit still bumps its version.
            item.touch();
        }
        repository.flush();
        return ItemResponse.from(item);
    }

    public void delete(UUID id) {
        Item item = load(id);
        List<String> reasons = usages.stream().flatMap(u -> u.describeUse(id).stream()).toList();
        if (!reasons.isEmpty()) {
            throw ApiException.conflict("Item " + item.getItemId() + " can't be deleted: " + String.join("; ", reasons)
                    + ". Mark it Inactive instead.", Map.of());
        }
        repository.delete(item);
    }

    private Item load(UUID id) {
        return repository.findById(id).orElseThrow(() -> ApiException.notFound("Item not found"));
    }

    private void apply(Item item, ItemRequest request, boolean isNew) {
        Map<String, String> errors = new LinkedHashMap<>();

        boolean anyDimension = request.width() != null || request.depth() != null || request.height() != null;
        if (anyDimension) {
            if (request.width() == null) errors.put("width", "required when any dimension is given");
            if (request.depth() == null) errors.put("depth", "required when any dimension is given");
            if (request.height() == null) errors.put("height", "required when any dimension is given");
            if (request.dimensionUom() == null) errors.put("dimensionUom", "required when dimensions are given");
        }
        if (request.weight() != null && request.weightUom() == null) {
            errors.put("weightUom", "required when weight is given");
        }
        if (request.shelfLifeDays() != null && !request.tracksExpiry()) {
            errors.put("shelfLifeDays", "only used when expiry dates are tracked");
        }

        // On update, leaving out "uoms" keeps the current packaging.
        List<ItemUomRequest> packs = request.uoms() != null ? request.uoms() : isNew ? List.of() : null;
        Map<UUID, UnitOfMeasure> units = packs == null ? Map.of() : checkPacks(item, packs, errors);
        if (!errors.isEmpty()) {
            throw ApiException.invalid("Some fields need attention", errors);
        }

        String itemId = Codes.normalize(request.itemId());
        String barcode = blankToNull(request.barcode());
        UUID self = item.getId();

        repository.findByOwnerIdAndItemId(item.getOwnerId(), itemId)
                .filter(other -> !other.getId().equals(self))
                .ifPresent(other -> errors.put("itemId", "already used by another item"));
        if (barcode != null) {
            barcodeOwner(barcode, item).ifPresent(owner -> errors.put("barcode", "already assigned to " + owner));
        }
        if (packs == null && barcode != null
                && item.getUoms().stream().anyMatch(p -> barcode.equals(p.getBarcode()))) {
            errors.put("barcode", "already used by one of this item's packaging levels");
        }
        if (packs != null) {
            for (int i = 0; i < packs.size(); i++) {
                String packBarcode = blankToNull(packs.get(i).barcode());
                if (packBarcode == null) continue;
                if (packBarcode.equals(barcode)) {
                    errors.put("uoms[" + i + "].barcode", "same as the item's own barcode");
                } else {
                    final int index = i;
                    barcodeOwner(packBarcode, item).ifPresent(owner ->
                            errors.put("uoms[" + index + "].barcode", "already assigned to " + owner));
                }
            }
        }
        if (!errors.isEmpty()) {
            throw ApiException.conflict("An item with these details already exists", errors);
        }

        item.setItemId(itemId);
        item.setDescription(request.description().trim());
        item.setStatus(request.status());
        item.setBarcode(barcode);
        item.setLotControlled(request.tracksLots());
        item.setSerialControlled(request.tracksSerials());
        item.setExpiryControlled(request.tracksExpiry());
        item.setShelfLifeDays(request.shelfLifeDays());
        item.setWidth(request.width());
        item.setDepth(request.depth());
        item.setHeight(request.height());
        item.setDimensionUom(anyDimension ? request.dimensionUom() : null);
        item.setWeight(request.weight());
        item.setWeightUom(request.weight() != null ? request.weightUom() : null);
        if (packs != null) {
            replacePacks(item, packs, units);
        }
    }

    /** Checks each packaging level; returns the units they refer to, keyed by id. */
    private Map<UUID, UnitOfMeasure> checkPacks(Item item, List<ItemUomRequest> packs, Map<String, String> errors) {
        Set<UUID> requested = packs.stream().map(ItemUomRequest::uomId).collect(Collectors.toSet());
        Map<UUID, UnitOfMeasure> units = uomRepository.findAllById(requested).stream()
                .collect(Collectors.toMap(UnitOfMeasure::getId, Function.identity()));
        Set<UUID> current = item.getUoms().stream().map(p -> p.getUom().getId()).collect(Collectors.toSet());
        Set<UUID> seenUnits = new HashSet<>();
        Map<String, Integer> seenBarcodes = new HashMap<>();

        for (int i = 0; i < packs.size(); i++) {
            ItemUomRequest pack = packs.get(i);
            String prefix = "uoms[" + i + "].";
            UnitOfMeasure unit = units.get(pack.uomId());
            if (unit == null) {
                errors.put(prefix + "uomId", "unit of measure not found");
            } else if (unit.isBase()) {
                errors.put(prefix + "uomId", unit.getUomCode() + " is the base unit; list only larger packs");
            } else if (unit.getStatus() == ActiveStatus.INACTIVE && !current.contains(unit.getId())) {
                errors.put(prefix + "uomId", unit.getUomCode() + " is inactive and can't be added");
            } else if (!seenUnits.add(unit.getId())) {
                errors.put(prefix + "uomId", unit.getUomCode() + " is listed twice");
            }

            boolean anyDimension = pack.width() != null || pack.depth() != null || pack.height() != null;
            if (anyDimension) {
                if (pack.width() == null) errors.put(prefix + "width", "required when any dimension is given");
                if (pack.depth() == null) errors.put(prefix + "depth", "required when any dimension is given");
                if (pack.height() == null) errors.put(prefix + "height", "required when any dimension is given");
                if (pack.dimensionUom() == null) errors.put(prefix + "dimensionUom", "required when dimensions are given");
            }
            if (pack.weight() != null && pack.weightUom() == null) {
                errors.put(prefix + "weightUom", "required when weight is given");
            }

            String barcode = blankToNull(pack.barcode());
            if (barcode != null) {
                Integer earlier = seenBarcodes.putIfAbsent(barcode, i);
                if (earlier != null) {
                    errors.put(prefix + "barcode", "same as another packaging level");
                }
            }
        }
        return units;
    }

    /** Updates packs in place where the unit is unchanged, adds new ones and removes the rest. */
    private void replacePacks(Item item, List<ItemUomRequest> packs, Map<UUID, UnitOfMeasure> units) {
        Map<UUID, ItemUom> existing = item.getUoms().stream()
                .collect(Collectors.toMap(p -> p.getUom().getId(), Function.identity()));
        Set<UUID> keep = packs.stream().map(ItemUomRequest::uomId).collect(Collectors.toSet());
        item.getUoms().removeIf(p -> !keep.contains(p.getUom().getId()));

        for (ItemUomRequest request : packs) {
            ItemUom pack = existing.get(request.uomId());
            if (pack == null) {
                pack = new ItemUom(UUID.randomUUID(), item, units.get(request.uomId()));
                item.getUoms().add(pack);
            }
            boolean anyDimension = request.width() != null;
            pack.setQuantity(request.quantity());
            pack.setBarcode(blankToNull(request.barcode()));
            pack.setWidth(request.width());
            pack.setDepth(request.depth());
            pack.setHeight(request.height());
            pack.setDimensionUom(anyDimension ? request.dimensionUom() : null);
            pack.setWeight(request.weight());
            pack.setWeightUom(request.weight() != null ? request.weightUom() : null);
        }
        item.getUoms().sort((a, b) -> Integer.compare(a.getQuantity(), b.getQuantity()));
    }

    /** Who else of the same owner already uses a barcode: another item, or another item's packaging level. */
    private Optional<String> barcodeOwner(String barcode, Item of) {
        UUID self = of.getId();
        Optional<String> asItem = repository.findByOwnerIdAndBarcode(of.getOwnerId(), barcode)
                .filter(other -> !other.getId().equals(self))
                .map(other -> "item " + other.getItemId());
        if (asItem.isPresent()) {
            return asItem;
        }
        return itemUomRepository.findFirstByBarcodeAndItem_OwnerId(barcode, of.getOwnerId())
                .filter(pack -> !pack.getItem().getId().equals(self))
                .map(pack -> pack.getUom().getUomCode() + " of item " + pack.getItem().getItemId());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
