package com.wms.masterdata.owner;

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

/** Owners, and finding them by code. */
@Service
@Transactional
public class Owners {

    private final OwnerRepository owners;

    Owners(OwnerRepository owners) {
        this.owners = owners;
    }

    public record OwnerView(UUID id, String code, String name, ActiveStatus status, long version) {
        static OwnerView of(Owner o) {
            return new OwnerView(o.getId(), o.getCode(), o.getName(), o.getStatus(), o.getVersion());
        }
    }

    public record OwnerRequest(
            @NotBlank @Size(max = 20) @Pattern(regexp = Codes.PATTERN, message = Codes.MESSAGE) String code,
            @NotBlank @Size(max = 120) String name,
            @NotNull ActiveStatus status,
            Long version) {
    }

    // ---------- For whatever has an owner ----------

    /** The owner with a code; the first one if none is said. */
    @Transactional(readOnly = true)
    public Owner named(String code) {
        String wanted = Codes.normalize(code);
        Owner owner = wanted == null ? owners.findById(Owner.FIRST).orElseThrow() : owners.findByCode(wanted).orElse(null);
        if (owner == null) {
            throw ApiException.invalid(wanted + " is not a known owner", Map.of("owner", wanted + " not found"));
        }
        return owner;
    }

    /** The same, for something new. */
    @Transactional(readOnly = true)
    public Owner forNew(String code) {
        Owner owner = named(code);
        if (owner.getStatus() == ActiveStatus.INACTIVE) {
            throw ApiException.invalid(owner.getCode() + " is inactive", Map.of("owner", owner.getCode() + " is inactive"));
        }
        return owner;
    }

    /** The same, for something started on a device: if none is said and there are several, asks which. */
    @Transactional(readOnly = true)
    public Owner chosen(String code) {
        if (Codes.normalize(code) == null) {
            List<String> active = owners.findAllByOrderByCodeAsc().stream().filter(o -> o.getStatus() == ActiveStatus.ACTIVE).map(Owner::getCode).toList();
            if (active.size() > 1) {
                throw ApiException.conflict("Say whose it is.", Map.of("owner", String.join(",", active)));
            }
        }
        return forNew(code);
    }

    /** For filters: null if none is said, and an owner that has nothing if it isn't known. */
    @Transactional(readOnly = true)
    public UUID filter(String code) {
        String wanted = Codes.normalize(code);
        return wanted == null ? null : owners.findByCode(wanted).map(Owner::getId).orElse(new UUID(0, 0));
    }

    @Transactional(readOnly = true)
    public Owner get(UUID id) {
        return owners.findById(id).orElseThrow();
    }

    // ---------- Keeping them ----------

    @Transactional(readOnly = true)
    public List<OwnerView> list() {
        return owners.findAllByOrderByCodeAsc().stream().map(OwnerView::of).toList();
    }

    public OwnerView create(OwnerRequest request) {
        Owner owner = new Owner(UUID.randomUUID());
        apply(owner, request);
        return OwnerView.of(owners.saveAndFlush(owner));
    }

    public OwnerView update(UUID id, OwnerRequest request) {
        Owner owner = owners.findById(id).orElseThrow(() -> ApiException.notFound("Owner not found"));
        if (request.version() == null) {
            throw ApiException.invalid("Version is required when updating an owner", Map.of("version", "required"));
        }
        if (request.version() != owner.getVersion()) {
            throw ApiException.conflict("This owner was changed by someone else. Reload it and try again.", Map.of());
        }
        if (request.status() == ActiveStatus.INACTIVE && id.equals(Owner.FIRST)) {
            throw ApiException.conflict(owner.getCode() + " is what is meant where no owner is said, so it stays active.",
                    Map.of("status", "has to stay active"));
        }
        apply(owner, request);
        return OwnerView.of(owners.saveAndFlush(owner));
    }

    private void apply(Owner owner, OwnerRequest request) {
        String code = Codes.normalize(request.code());
        owners.findByCode(code).filter(other -> !other.getId().equals(owner.getId())).ifPresent(other -> {
            throw ApiException.conflict("An owner with this code already exists", Map.of("code", "already used by another owner"));
        });
        owner.setCode(code);
        owner.setName(request.name().trim());
        owner.setStatus(request.status());
    }
}
