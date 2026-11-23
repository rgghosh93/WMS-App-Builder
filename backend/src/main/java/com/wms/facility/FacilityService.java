package com.wms.facility;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wms.common.ActiveStatus;
import com.wms.common.Codes;
import com.wms.common.api.ApiException;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Service
@Transactional
public class FacilityService {

    private final FacilityRepository facilities;

    FacilityService(FacilityRepository facilities) {
        this.facilities = facilities;
    }

    public record FacilityView(UUID id, String code, String name, String address, ActiveStatus status, long version) {
        static FacilityView of(Facility f) {
            return new FacilityView(f.getId(), f.getCode(), f.getName(), f.getAddress(), f.getStatus(), f.getVersion());
        }
    }

    public record FacilityRequest(
            @NotBlank @Size(max = 20) @Pattern(regexp = Codes.PATTERN, message = Codes.MESSAGE) String code,
            @NotBlank @Size(max = 120) String name,
            @Size(max = 400) String address,
            @NotNull ActiveStatus status,
            Long version) {
    }

    @Transactional(readOnly = true)
    public List<FacilityView> list() {
        return facilities.findAllByOrderByCodeAsc().stream().map(FacilityView::of).toList();
    }

    public FacilityView create(FacilityRequest request) {
        Facility facility = new Facility(UUID.randomUUID());
        apply(facility, request);
        return FacilityView.of(facilities.saveAndFlush(facility));
    }

    public FacilityView update(UUID id, FacilityRequest request) {
        Facility facility = facilities.findById(id).orElseThrow(() -> ApiException.notFound("Facility not found"));
        if (request.version() == null) {
            throw ApiException.invalid("Version is required when updating a facility", Map.of("version", "required"));
        }
        if (request.version() != facility.getVersion()) {
            throw ApiException.conflict("This facility was changed by someone else. Reload it and try again.", Map.of());
        }
        if (request.status() == ActiveStatus.INACTIVE && id.equals(Site.current())) {
            throw ApiException.conflict("You are working in " + facility.getCode() + ". Go to another facility first.",
                    Map.of("status", "you are working in it"));
        }
        apply(facility, request);
        return FacilityView.of(facilities.saveAndFlush(facility));
    }

    private void apply(Facility facility, FacilityRequest request) {
        String code = Codes.normalize(request.code());
        facilities.findByCode(code).filter(other -> !other.getId().equals(facility.getId())).ifPresent(other -> {
            throw ApiException.conflict("A facility with this code already exists", Map.of("code", "already used by another facility"));
        });
        facility.setCode(code);
        facility.setName(request.name().trim());
        facility.setAddress(request.address() == null || request.address().isBlank() ? null : request.address().trim());
        facility.setStatus(request.status());
    }
}
