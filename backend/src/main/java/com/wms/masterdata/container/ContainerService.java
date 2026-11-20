package com.wms.masterdata.container;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wms.common.ActiveStatus;
import com.wms.common.Codes;
import com.wms.common.api.ApiException;
import com.wms.masterdata.location.Location;
import com.wms.masterdata.location.LocationRepository;

@Service
@Transactional
public class ContainerService {

    private final ContainerRepository repository;
    private final ContainerTypeRepository typeRepository;
    private final LocationRepository locationRepository;
    private final List<ContainerUsage> usages;

    public ContainerService(ContainerRepository repository, ContainerTypeRepository typeRepository,
                            LocationRepository locationRepository, List<ContainerUsage> usages) {
        this.repository = repository;
        this.typeRepository = typeRepository;
        this.locationRepository = locationRepository;
        this.usages = usages;
    }

    @Transactional(readOnly = true)
    public Page<ContainerResponse> search(ContainerSearch search, Pageable pageable) {
        return repository.findAll(search.toSpecification(), pageable).map(ContainerResponse::from);
    }

    @Transactional(readOnly = true)
    public ContainerResponse get(UUID id) {
        return ContainerResponse.from(load(id));
    }

    public ContainerResponse create(ContainerRequest request) {
        Container container = new Container(UUID.randomUUID());
        apply(container, request);
        return ContainerResponse.from(repository.save(container));
    }

    public ContainerResponse update(UUID id, ContainerRequest request) {
        Container container = load(id);
        if (request.version() == null) {
            throw ApiException.invalid("Version is required when updating a container", Map.of("version", "required"));
        }
        if (request.version() != container.getVersion()) {
            throw ApiException.conflict("This container was changed by someone else. Reload it and try again.", Map.of());
        }
        apply(container, request);
        return ContainerResponse.from(repository.saveAndFlush(container));
    }

    public void delete(UUID id) {
        Container container = load(id);
        List<String> reasons = new ArrayList<>();
        long children = repository.countByParent_Id(id);
        if (children > 0) {
            reasons.add(children == 1 ? "1 container is inside it" : children + " containers are inside it");
        }
        usages.forEach(u -> u.describeUse(id).ifPresent(reasons::add));
        if (!reasons.isEmpty()) {
            throw ApiException.conflict("Container " + container.getLpnId() + " can't be deleted: "
                    + String.join("; ", reasons) + ". Empty it first, or set it to Retired.", Map.of());
        }
        repository.delete(container);
    }

    /** Creates a numbered run of containers; all or nothing. */
    public RangeResult createRange(ContainerRangeRequest request) {
        String prefix = request.prefix() == null ? "" : Objects.requireNonNullElse(Codes.normalize(request.prefix()), "");
        long last = request.start() + request.count() - 1;
        if (String.valueOf(last).length() > request.digits()) {
            throw ApiException.invalid("The numbers don't fit in the digits chosen",
                    Map.of("digits", "needs at least " + String.valueOf(last).length() + " digits to reach " + last));
        }
        if (prefix.length() + request.digits() > 40) {
            throw ApiException.invalid("LPNs would be too long", Map.of("prefix", "prefix plus digits must be 40 characters or fewer"));
        }

        Map<String, String> errors = new LinkedHashMap<>();
        ContainerType type = resolveType(request.containerTypeId(), true, errors);
        Location location = resolveLocation(request.currentLocationId(), errors);
        if (!errors.isEmpty()) {
            throw ApiException.invalid("Some fields need attention", errors);
        }

        List<String> lpnIds = new ArrayList<>(request.count());
        for (long n = request.start(); n <= last; n++) {
            lpnIds.add(prefix + String.format("%0" + request.digits() + "d", n));
        }
        List<String> existing = repository.findExistingLpnIds(lpnIds);
        if (!existing.isEmpty()) {
            String examples = String.join(", ", existing.subList(0, Math.min(3, existing.size())));
            throw ApiException.conflict("Some of these LPNs already exist",
                    Map.of("start", existing.size() + " already exist, for example " + examples));
        }

        String notes = blankToNull(request.notes());
        List<Container> containers = lpnIds.stream().map(lpnId -> {
            Container c = new Container(UUID.randomUUID());
            c.setLpnId(lpnId);
            c.setContainerType(type);
            c.setStatus(request.status());
            c.setCurrentLocation(location);
            c.setNotes(notes);
            return c;
        }).toList();
        repository.saveAll(containers);
        return new RangeResult(containers.size(), lpnIds.getFirst(), lpnIds.getLast());
    }

    public record RangeResult(int created, String firstLpnId, String lastLpnId) {
    }

    private Container load(UUID id) {
        return repository.findWithDetailsById(id).orElseThrow(() -> ApiException.notFound("Container not found"));
    }

    private void apply(Container container, ContainerRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        UUID currentTypeId = container.getContainerType() == null ? null : container.getContainerType().getId();
        boolean typeChanging = !request.containerTypeId().equals(currentTypeId);
        ContainerType type = resolveType(request.containerTypeId(), typeChanging, errors);
        Location location = resolveLocation(request.currentLocationId(), errors);
        if (location != null && container.getParent() != null) {
            errors.put("currentLocationId", "this container is inside " + container.getParent().getLpnId()
                    + ", so it takes that container's location");
        }
        if (!errors.isEmpty()) {
            throw ApiException.invalid("Some fields need attention", errors);
        }

        String lpnId = Codes.normalize(request.lpnId());
        repository.findByLpnId(lpnId)
                .filter(other -> !other.getId().equals(container.getId()))
                .ifPresent(other -> {
                    throw ApiException.conflict("A container with this LPN already exists",
                            Map.of("lpnId", "already used by another container"));
                });

        container.setLpnId(lpnId);
        container.setContainerType(type);
        container.setStatus(request.status());
        container.setCurrentLocation(location);
        container.setNotes(blankToNull(request.notes()));
    }

    /**
     * Permanent containers must be of a reusable type. An inactive type can stay
     * on containers that already have it, but can't be newly assigned.
     */
    private ContainerType resolveType(UUID id, boolean newlyAssigned, Map<String, String> errors) {
        ContainerType type = typeRepository.findById(id).orElse(null);
        if (type == null) {
            errors.put("containerTypeId", "container type not found");
        } else if (!type.isReusable()) {
            errors.put("containerTypeId", type.getContainerTypeId() + " is single-use; permanent containers need a reusable type");
        } else if (newlyAssigned && type.getStatus() == ActiveStatus.INACTIVE) {
            errors.put("containerTypeId", type.getContainerTypeId() + " is inactive and can't be assigned");
        }
        return type;
    }

    private Location resolveLocation(UUID id, Map<String, String> errors) {
        if (id == null) {
            return null;
        }
        Location location = locationRepository.findById(id).orElse(null);
        if (location == null) {
            errors.put("currentLocationId", "location not found");
        }
        return location;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
