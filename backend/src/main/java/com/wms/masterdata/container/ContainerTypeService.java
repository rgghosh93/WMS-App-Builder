package com.wms.masterdata.container;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wms.common.Codes;
import com.wms.common.api.ApiException;

@Service
@Transactional
public class ContainerTypeService {

    private final ContainerTypeRepository repository;
    private final ContainerRepository containerRepository;

    public ContainerTypeService(ContainerTypeRepository repository, ContainerRepository containerRepository) {
        this.repository = repository;
        this.containerRepository = containerRepository;
    }

    @Transactional(readOnly = true)
    public Page<ContainerType> search(ContainerTypeSearch search, Pageable pageable) {
        return repository.findAll(search.toSpecification(), pageable);
    }

    @Transactional(readOnly = true)
    public ContainerType get(UUID id) {
        return repository.findById(id).orElseThrow(() -> ApiException.notFound("Container type not found"));
    }

    public ContainerType create(ContainerTypeRequest request) {
        ContainerType type = new ContainerType(UUID.randomUUID());
        apply(type, request);
        return repository.save(type);
    }

    public ContainerType update(UUID id, ContainerTypeRequest request) {
        ContainerType type = get(id);
        if (request.version() == null) {
            throw ApiException.invalid("Version is required when updating a container type", Map.of("version", "required"));
        }
        if (request.version() != type.getVersion()) {
            throw ApiException.conflict("This container type was changed by someone else. Reload it and try again.", Map.of());
        }
        apply(type, request);
        return repository.saveAndFlush(type);
    }

    public void delete(UUID id) {
        ContainerType type = get(id);
        long used = containerRepository.countByContainerType_Id(id);
        if (used > 0) {
            throw ApiException.conflict("Container type " + type.getContainerTypeId() + " is used by " + used
                    + (used == 1 ? " container" : " containers") + " and can't be deleted. Mark it Inactive instead.", Map.of());
        }
        repository.delete(type);
    }

    private void apply(ContainerType type, ContainerTypeRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (request.tareWeight() != null && request.tareWeightUom() == null) {
            errors.put("tareWeightUom", "required when tare weight is given");
        }
        if (request.maxWeight() != null && request.maxWeightUom() == null) {
            errors.put("maxWeightUom", "required when max weight is given");
        }
        if (request.maxVolume() != null && request.maxVolumeUom() == null) {
            errors.put("maxVolumeUom", "required when max volume is given");
        }
        if (!request.isReusable() && type.isReusable()) {
            long used = containerRepository.countByContainerType_Id(type.getId());
            if (used > 0) {
                errors.put("reusable", "used by " + used + (used == 1 ? " permanent container" : " permanent containers")
                        + ", so it must stay reusable");
            }
        }
        if (!errors.isEmpty()) {
            throw ApiException.invalid("Some fields need attention", errors);
        }

        String containerTypeId = Codes.normalize(request.containerTypeId());
        repository.findByContainerTypeId(containerTypeId)
                .filter(other -> !other.getId().equals(type.getId()))
                .ifPresent(other -> {
                    throw ApiException.conflict("A container type with this ID already exists",
                            Map.of("containerTypeId", "already used by another container type"));
                });

        type.setContainerTypeId(containerTypeId);
        type.setDescription(request.description().trim());
        type.setCategory(request.category());
        type.setStatus(request.status());
        type.setReusable(request.isReusable());
        type.setWidth(request.width());
        type.setDepth(request.depth());
        type.setHeight(request.height());
        type.setDimensionUom(request.dimensionUom());
        type.setTareWeight(request.tareWeight());
        type.setTareWeightUom(request.tareWeight() != null ? request.tareWeightUom() : null);
        type.setMaxWeight(request.maxWeight());
        type.setMaxWeightUom(request.maxWeight() != null ? request.maxWeightUom() : null);
        type.setMaxVolume(request.maxVolume());
        type.setMaxVolumeUom(request.maxVolume() != null ? request.maxVolumeUom() : null);
    }
}
