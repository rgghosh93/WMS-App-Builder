package com.wms.masterdata.carrier;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wms.common.Codes;
import com.wms.common.api.ApiException;

/** Looks after carriers and the services they offer. */
@Service
@Transactional
public class CarriersService {

    private final CarrierRepository repository;
    private final List<CarrierUsage> usages;

    public CarriersService(CarrierRepository repository, List<CarrierUsage> usages) {
        this.repository = repository;
        this.usages = usages;
    }

    /** All carriers. The list is small, so it is not paged. */
    @Transactional(readOnly = true)
    public List<CarrierResponse> list() {
        return repository.findAll(Sort.by("carrierCode")).stream().map(CarrierResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public CarrierResponse get(UUID id) {
        return CarrierResponse.from(load(id));
    }

    public CarrierResponse create(CarrierRequest request) {
        Carrier carrier = new Carrier(UUID.randomUUID());
        apply(carrier, request);
        return CarrierResponse.from(repository.saveAndFlush(carrier));
    }

    public CarrierResponse update(UUID id, CarrierRequest request) {
        Carrier carrier = load(id);
        if (request.version() == null) {
            throw ApiException.invalid("Version is required when updating a carrier", Map.of("version", "required"));
        }
        if (request.version() != carrier.getVersion()) {
            throw ApiException.conflict("This carrier was changed by someone else. Reload it and try again.", Map.of());
        }
        apply(carrier, request);
        carrier.touch();
        return CarrierResponse.from(repository.saveAndFlush(carrier));
    }

    public void delete(UUID id) {
        Carrier carrier = load(id);
        List<String> reasons = usages.stream().flatMap(u -> u.describeUse(id).stream()).toList();
        if (!reasons.isEmpty()) {
            throw ApiException.conflict(carrier.getCarrierCode() + " can't be deleted: " + String.join("; ", reasons)
                    + ". Mark it Inactive instead.", Map.of());
        }
        repository.delete(carrier);
    }

    private Carrier load(UUID id) {
        return repository.findById(id).orElseThrow(() -> ApiException.notFound("Carrier not found"));
    }

    private void apply(Carrier carrier, CarrierRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        Set<String> codes = new HashSet<>();
        Set<UUID> kept = new HashSet<>();
        for (int i = 0; i < request.services().size(); i++) {
            CarrierRequest.Service s = request.services().get(i);
            if (!codes.add(Codes.normalize(s.serviceCode()))) {
                errors.put("services[" + i + "].serviceCode", "used twice");
            }
            if (s.id() != null && (carrier.getServices().stream().noneMatch(e -> e.getId().equals(s.id())) || !kept.add(s.id()))) {
                errors.put("services[" + i + "].serviceCode", "service not found on this carrier");
            }
        }
        if (!errors.isEmpty()) {
            throw ApiException.invalid("Some services need attention", errors);
        }
        String code = Codes.normalize(request.carrierCode());
        repository.findByCarrierCode(code)
                .filter(other -> !other.getId().equals(carrier.getId()))
                .ifPresent(other -> {
                    throw ApiException.conflict("A carrier with this code already exists",
                            Map.of("carrierCode", "already used by another carrier"));
                });
        for (CarrierService existing : carrier.getServices()) {
            if (!kept.contains(existing.getId()) && usages.stream().anyMatch(u -> u.usesService(existing.getId()))) {
                throw ApiException.conflict("Service " + existing.getServiceCode()
                        + " can't be removed: shipments use it. Mark it Inactive instead.", Map.of());
            }
        }

        carrier.setCarrierCode(code);
        carrier.setName(request.name().trim());
        carrier.setMode(request.mode());
        carrier.setTrackingRequired(request.trackingRequired());
        carrier.setStatus(request.status());
        carrier.getServices().removeIf(e -> !kept.contains(e.getId()));
        for (CarrierRequest.Service s : request.services()) {
            CarrierService service = carrier.getServices().stream().filter(e -> e.getId().equals(s.id())).findFirst().orElse(null);
            if (service == null) {
                service = new CarrierService(UUID.randomUUID(), carrier, Codes.normalize(s.serviceCode()), s.name().trim());
                carrier.getServices().add(service);
            }
            service.setServiceCode(Codes.normalize(s.serviceCode()));
            service.setName(s.name().trim());
            service.setStatus(s.status());
        }
    }
}
