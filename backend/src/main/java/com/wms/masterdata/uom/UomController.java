package com.wms.masterdata.uom;

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

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/uoms")
public class UomController {

    private final UomService service;

    public UomController(UomService service) {
        this.service = service;
    }

    @GetMapping
    public List<UomResponse> list() {
        return service.list().stream().map(UomResponse::from).toList();
    }

    @GetMapping("/{id}")
    public UomResponse get(@PathVariable UUID id) {
        return UomResponse.from(service.get(id));
    }

    @PostMapping
    public ResponseEntity<UomResponse> create(@Valid @RequestBody UomRequest request) {
        UomResponse created = UomResponse.from(service.create(request));
        return ResponseEntity.created(URI.create("/api/uoms/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    public UomResponse update(@PathVariable UUID id, @Valid @RequestBody UomRequest request) {
        return UomResponse.from(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }
}
