package com.wms.users;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.wms.common.ActiveStatus;
import com.wms.common.api.ApiException;
import com.wms.common.api.PageResponse;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/users")
@Requires(Permission.USERS)
public class UserController {

    private static final int MAX_PAGE_SIZE = 200;
    private static final Map<String, String> SORT = Map.of(
            "userCode", "userCode", "displayName", "displayName", "status", "status", "lastSignInAt", "lastSignInAt");

    private final UserService service;

    public UserController(UserService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<UserView> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) ActiveStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "userCode") String sort,
            @RequestParam(defaultValue = "asc") String dir) {
        String path = SORT.get(sort);
        if (path == null) {
            throw ApiException.invalid("Cannot sort by '" + sort + "'", Map.of("sort", "must be one of " + SORT.keySet()));
        }
        Sort.Direction direction = "desc".equalsIgnoreCase(dir) ? Sort.Direction.DESC : Sort.Direction.ASC;
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE),
                Sort.by(new Sort.Order(direction, path).nullsLast()).and(Sort.by("userCode")));
        return PageResponse.of(service.search(new UserSearch(q, status), pageable), u -> u);
    }

    @GetMapping("/{id}")
    public UserView get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<UserView> create(@Valid @RequestBody UserRequests.Create request) {
        UserView created = service.create(request);
        return ResponseEntity.created(URI.create("/api/users/" + created.id())).body(created);
    }

    @PutMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public UserView update(@PathVariable UUID id, @Valid @RequestBody UserRequests.Update request) {
        return service.update(id, request);
    }

    @PutMapping(value = "/{id}/pin", consumes = MediaType.APPLICATION_JSON_VALUE)
    public UserView setPin(@PathVariable UUID id, @RequestBody UserRequests.NewPin request) {
        return service.setPin(id, request.pin());
    }

    @PutMapping(value = "/{id}/password", consumes = MediaType.APPLICATION_JSON_VALUE)
    public UserView setPassword(@PathVariable UUID id, @RequestBody UserRequests.NewPassword request) {
        return service.setPassword(id, request.password());
    }

    @PostMapping(value = "/{id}/unlock", consumes = MediaType.APPLICATION_JSON_VALUE)
    public UserView unlock(@PathVariable UUID id) {
        return service.unlock(id);
    }

    @PostMapping(value = "/{id}/sign-out", consumes = MediaType.APPLICATION_JSON_VALUE)
    public UserView signOut(@PathVariable UUID id) {
        return service.signOut(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }
}
