package com.wms.control;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wms.common.ActiveStatus;
import com.wms.common.Codes;
import com.wms.common.api.ApiException;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Reason codes and the approval limit. */
@Service
@Transactional
public class ReasonService {

    private final ReasonCodeRepository reasons;
    private final InventorySettingsRepository settings;

    ReasonService(ReasonCodeRepository reasons, InventorySettingsRepository settings) {
        this.reasons = reasons;
        this.settings = settings;
    }

    public record ReasonView(UUID id, ReasonCode.Kind kind, String code, String name, ActiveStatus status, long version) {
        static ReasonView of(ReasonCode r) {
            return new ReasonView(r.getId(), r.getKind(), r.getCode(), r.getName(), r.getStatus(), r.getVersion());
        }
    }

    public record ReasonRequest(
            @NotNull ReasonCode.Kind kind,
            @NotBlank @Size(max = 20) @Pattern(regexp = Codes.PATTERN, message = Codes.MESSAGE) String code,
            @NotBlank @Size(max = 80) String name,
            @NotNull ActiveStatus status,
            Long version) {
    }

    public record SettingsView(int approvalLimit) {
    }

    public record SettingsRequest(@NotNull @Min(0) @Max(1_000_000) Integer approvalLimit) {
    }

    @Transactional(readOnly = true)
    public List<ReasonView> list() {
        return reasons.findAllByOrderByKindAscNameAsc().stream().map(ReasonView::of).toList();
    }

    public ReasonView create(ReasonRequest request) {
        ReasonCode reason = new ReasonCode(UUID.randomUUID(), request.kind());
        apply(reason, request);
        return ReasonView.of(reasons.saveAndFlush(reason));
    }

    /** History keeps the name a reason had at the time. */
    public ReasonView update(UUID id, ReasonRequest request) {
        ReasonCode reason = reasons.findById(id).orElseThrow(() -> ApiException.notFound("Reason not found"));
        if (request.version() == null) {
            throw ApiException.invalid("Version is required when updating a reason", Map.of("version", "required"));
        }
        if (request.version() != reason.getVersion()) {
            throw ApiException.conflict("This reason was changed by someone else. Reload it and try again.", Map.of());
        }
        if (request.kind() != reason.getKind()) {
            throw ApiException.invalid("Some fields need attention", Map.of("kind", "can't be changed"));
        }
        apply(reason, request);
        return ReasonView.of(reasons.saveAndFlush(reason));
    }

    public void delete(UUID id) {
        reasons.delete(reasons.findById(id).orElseThrow(() -> ApiException.notFound("Reason not found")));
    }

    @Transactional(readOnly = true)
    public SettingsView settings() {
        return new SettingsView(approvalLimit());
    }

    public SettingsView update(SettingsRequest request) {
        settings.findById(InventorySettings.ID).orElseThrow().setApprovalLimit(request.approvalLimit());
        return new SettingsView(request.approvalLimit());
    }

    int approvalLimit() {
        return settings.findById(InventorySettings.ID).orElseThrow().getApprovalLimit();
    }

    /** "Damaged: fell off the rack". */
    String text(UUID reasonId, ReasonCode.Kind kind, String note) {
        ReasonCode reason = reasonId == null ? null : reasons.findById(reasonId).orElse(null);
        if (reason == null || reason.getKind() != kind || reason.getStatus() != ActiveStatus.ACTIVE) {
            throw ApiException.invalid("Choose a reason", Map.of("reasonId", "choose a reason"));
        }
        if (note != null && note.length() > 250) {
            throw ApiException.invalid("That note is too long", Map.of("note", "at most 250 characters"));
        }
        return note == null || note.isBlank() ? reason.getName() : reason.getName() + ": " + note.trim();
    }

    private void apply(ReasonCode reason, ReasonRequest request) {
        String code = Codes.normalize(request.code());
        reasons.findByKindAndCode(request.kind(), code).filter(other -> !other.getId().equals(reason.getId())).ifPresent(other -> {
            throw ApiException.conflict("A reason with this code already exists", Map.of("code", "already used by another reason"));
        });
        reason.setCode(code);
        reason.setName(request.name().trim());
        reason.setStatus(request.status());
    }
}
