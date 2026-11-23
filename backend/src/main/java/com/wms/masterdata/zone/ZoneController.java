package com.wms.masterdata.zone;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.wms.common.api.PageResponse;
import com.wms.masterdata.location.LocationResponse;
import com.wms.masterdata.zone.ZoneService.AddRequest;
import com.wms.masterdata.zone.ZoneService.Filter;
import com.wms.masterdata.zone.ZoneService.FunctionView;
import com.wms.masterdata.zone.ZoneService.Preview;
import com.wms.masterdata.zone.ZoneService.ZoneOfLocation;
import com.wms.masterdata.zone.ZoneService.ZoneRequest;
import com.wms.masterdata.zone.ZoneService.ZoneView;
import com.wms.users.Permission;
import com.wms.users.Requires;

import jakarta.validation.Valid;

@RestController
public class ZoneController {

    private static final int MAX_PAGE_SIZE = 200;

    private final ZoneService service;

    public ZoneController(ZoneService service) {
        this.service = service;
    }

    public record RemoveRequest(List<UUID> locationIds) {
    }

    @GetMapping("/api/zones/functions")
    public List<FunctionView> functions() {
        return service.functions();
    }

    @GetMapping("/api/zones")
    public List<ZoneView> list(@RequestParam(required = false) ZoneFunction function) {
        return service.list(function);
    }

    @GetMapping("/api/zones/{id}")
    public ZoneView get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping("/api/zones")
    @Requires(Permission.MASTER_DATA)
    public ResponseEntity<ZoneView> create(@Valid @RequestBody ZoneRequest request) {
        ZoneView created = service.create(request);
        return ResponseEntity.created(URI.create("/api/zones/" + created.id())).body(created);
    }

    @PutMapping("/api/zones/{id}")
    @Requires(Permission.MASTER_DATA)
    public ZoneView update(@PathVariable UUID id, @Valid @RequestBody ZoneRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/api/zones/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Requires(Permission.MASTER_DATA)
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }

    @GetMapping("/api/zones/{id}/locations")
    public PageResponse<LocationResponse> locations(@PathVariable UUID id, @RequestParam(required = false) String q,
                                                    @RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "25") int size) {
        return PageResponse.of(service.locations(id, q, PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE))),
                LocationResponse::from);
    }

    /** What adding locations would do. */
    @PostMapping("/api/zones/{id}/locations/preview")
    @Requires(Permission.MASTER_DATA)
    public Preview preview(@PathVariable UUID id, @RequestBody Filter filter) {
        return service.preview(id, filter);
    }

    @PostMapping("/api/zones/{id}/locations")
    @Requires(Permission.MASTER_DATA)
    public Preview add(@PathVariable UUID id, @Valid @RequestBody AddRequest request) {
        return service.add(id, request);
    }

    @PostMapping("/api/zones/{id}/locations/remove")
    @Requires(Permission.MASTER_DATA)
    public ZoneView remove(@PathVariable UUID id, @RequestBody RemoveRequest request) {
        return service.remove(id, request.locationIds());
    }

    /** The zones a location is in. */
    @GetMapping("/api/locations/{id}/zones")
    public List<ZoneOfLocation> ofLocation(@PathVariable UUID id) {
        return service.ofLocation(id);
    }
}
