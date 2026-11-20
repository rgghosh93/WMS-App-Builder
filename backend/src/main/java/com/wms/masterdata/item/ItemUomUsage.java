package com.wms.masterdata.item;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.wms.masterdata.uom.UomUsage;

/** Stops a unit of measure from being deleted while items use it as a packaging level. */
@Component
class ItemUomUsage implements UomUsage {

    private final ItemUomRepository repository;

    ItemUomUsage(ItemUomRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<String> describeUse(UUID uomId) {
        long count = repository.countByUom_Id(uomId);
        if (count == 0) {
            return Optional.empty();
        }
        return Optional.of(count == 1 ? "1 item uses it" : count + " items use it");
    }
}
