package com.wms.masterdata.owner;

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

import com.wms.masterdata.owner.Owners.OwnerRequest;
import com.wms.masterdata.owner.Owners.OwnerView;
import com.wms.users.Permission;
import com.wms.users.Requires;

import jakarta.validation.Valid;

/** Owners are never deleted, so the records still say whose stock it was. They are made inactive. */
@RestController
@RequestMapping("/api/owners")
public class OwnerController {

    private final Owners owners;

    public OwnerController(Owners owners) {
        this.owners = owners;
    }

    @GetMapping
    public List<OwnerView> list() {
        return owners.list();
    }

    @PostMapping
    @Requires(Permission.MASTER_DATA)
    public ResponseEntity<OwnerView> create(@Valid @RequestBody OwnerRequest request) {
        OwnerView created = owners.create(request);
        return ResponseEntity.created(URI.create("/api/owners/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    @Requires(Permission.MASTER_DATA)
    public OwnerView update(@PathVariable UUID id, @Valid @RequestBody OwnerRequest request) {
        return owners.update(id, request);
    }
}
