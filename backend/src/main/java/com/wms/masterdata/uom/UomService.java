package com.wms.masterdata.uom;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wms.common.ActiveStatus;
import com.wms.common.Codes;
import com.wms.common.api.ApiException;

@Service
@Transactional
public class UomService {

    private final UnitOfMeasureRepository repository;
    private final List<UomUsage> usages;

    public UomService(UnitOfMeasureRepository repository, List<UomUsage> usages) {
        this.repository = repository;
        this.usages = usages;
    }

    /** All units, base unit first. The list is small, so it is not paged. */
    @Transactional(readOnly = true)
    public List<UnitOfMeasure> list() {
        return repository.findAll(Sort.by(Sort.Order.desc("base"), Sort.Order.asc("uomCode")));
    }

    @Transactional(readOnly = true)
    public UnitOfMeasure get(UUID id) {
        return repository.findById(id).orElseThrow(() -> ApiException.notFound("Unit of measure not found"));
    }

    public UnitOfMeasure create(UomRequest request) {
        UnitOfMeasure uom = new UnitOfMeasure(UUID.randomUUID());
        apply(uom, request);
        return repository.save(uom);
    }

    public UnitOfMeasure update(UUID id, UomRequest request) {
        UnitOfMeasure uom = get(id);
        if (request.version() == null) {
            throw ApiException.invalid("Version is required when updating a unit of measure", Map.of("version", "required"));
        }
        if (request.version() != uom.getVersion()) {
            throw ApiException.conflict("This unit of measure was changed by someone else. Reload it and try again.", Map.of());
        }
        apply(uom, request);
        return repository.saveAndFlush(uom);
    }

    public void delete(UUID id) {
        UnitOfMeasure uom = get(id);
        if (uom.isBase()) {
            throw ApiException.conflict(uom.getUomCode() + " is the base unit and can't be deleted.", Map.of());
        }
        List<String> reasons = usages.stream().flatMap(u -> u.describeUse(id).stream()).toList();
        if (!reasons.isEmpty()) {
            throw ApiException.conflict(uom.getUomCode() + " can't be deleted: " + String.join("; ", reasons)
                    + ". Mark it Inactive instead.", Map.of());
        }
        repository.delete(uom);
    }

    private void apply(UnitOfMeasure uom, UomRequest request) {
        String code = Codes.normalize(request.uomCode());
        if (uom.isBase()) {
            // The base unit's code, kind and status are fixed; only the description can change.
            if (!code.equals(uom.getUomCode()) || request.kind() != UomKind.EACH || request.status() != ActiveStatus.ACTIVE) {
                throw ApiException.invalid("Only the description of the base unit can be changed",
                        Map.of("uomCode", "the base unit stays " + uom.getUomCode() + ", Each, Active"));
            }
        } else if (request.kind() == UomKind.EACH) {
            throw ApiException.invalid("Some fields need attention",
                    Map.of("kind", "Each is reserved for the base unit; choose the packaging level this unit holds"));
        }

        repository.findByUomCode(code)
                .filter(other -> !other.getId().equals(uom.getId()))
                .ifPresent(other -> {
                    throw ApiException.conflict("A unit of measure with this code already exists",
                            Map.of("uomCode", "already used by another unit of measure"));
                });

        uom.setUomCode(code);
        uom.setDescription(request.description().trim());
        uom.setKind(request.kind());
        uom.setStatus(request.status());
    }
}
