package com.wms.facility;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.wms.facility.FacilityService.FacilityRequest;
import com.wms.facility.FacilityService.FacilityView;
import com.wms.users.Permission;
import com.wms.users.Requires;

import jakarta.validation.Valid;

/** Facilities are never deleted, so the records still say where things happened. They are made inactive. */
@RestController
@RequestMapping("/api/facilities")
public class FacilityController {

    private final FacilityService service;

    public FacilityController(FacilityService service) {
        this.service = service;
    }

    @GetMapping
    public List<FacilityView> list() {
        return service.list();
    }

    @PostMapping
    @Requires(Permission.FACILITIES)
    public ResponseEntity<FacilityView> create(@Valid @RequestBody FacilityRequest request) {
        FacilityView created = service.create(request);
        return ResponseEntity.created(URI.create("/api/facilities/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    @Requires(Permission.FACILITIES)
    public FacilityView update(@PathVariable UUID id, @Valid @RequestBody FacilityRequest request) {
        return service.update(id, request);
    }
}
