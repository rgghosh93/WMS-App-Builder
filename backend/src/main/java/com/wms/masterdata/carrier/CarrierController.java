package com.wms.masterdata.carrier;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import com.wms.users.Permission;
import com.wms.users.Requires;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/carriers")
public class CarrierController {

    private final CarriersService service;

    public CarrierController(CarriersService service) {
        this.service = service;
    }

    @GetMapping
    public List<CarrierResponse> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public CarrierResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping
    @Requires(Permission.MASTER_DATA)
    public ResponseEntity<CarrierResponse> create(@Valid @RequestBody CarrierRequest request) {
        CarrierResponse created = service.create(request);
        return ResponseEntity.created(URI.create("/api/carriers/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    @Requires(Permission.MASTER_DATA)
    public CarrierResponse update(@PathVariable UUID id, @Valid @RequestBody CarrierRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    @Requires(Permission.MASTER_DATA)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }
}
