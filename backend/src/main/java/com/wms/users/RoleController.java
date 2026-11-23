package com.wms.users;

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

import com.wms.users.RoleService.PermissionView;
import com.wms.users.RoleService.RoleView;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/roles")
@Requires(Permission.USERS)
public class RoleController {

    private final RoleService service;

    public RoleController(RoleService service) {
        this.service = service;
    }

    @GetMapping
    public List<RoleView> list() {
        return service.list();
    }

    @GetMapping("/permissions")
    public List<PermissionView> permissions() {
        return service.permissions();
    }

    @PostMapping
    public ResponseEntity<RoleView> create(@Valid @RequestBody UserRequests.RoleInput input) {
        RoleView created = service.create(input);
        return ResponseEntity.created(URI.create("/api/roles/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    public RoleView update(@PathVariable UUID id, @Valid @RequestBody UserRequests.RoleInput input) {
        return service.update(id, input);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }
}
