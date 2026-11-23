package com.wms.users;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wms.common.api.ApiException;

@Service
@Transactional
public class RoleService {

    private final RoleRepository roles;
    private final AppUserRepository users;

    RoleService(RoleRepository roles, AppUserRepository users) {
        this.roles = roles;
        this.users = users;
    }

    /** @param administrator has every permission, and can't be changed */
    public record RoleView(UUID id, String name, String description, boolean administrator, List<Permission> permissions,
                           long userCount, long version) {
    }

    public record PermissionView(Permission id, Permission.Group group, String label, String description) {
    }

    @Transactional(readOnly = true)
    public List<RoleView> list() {
        return roles.findAllInOrder().stream().map(this::view).toList();
    }

    public List<PermissionView> permissions() {
        return Arrays.stream(Permission.values())
                .map(p -> new PermissionView(p, p.group(), p.label(), p.description())).toList();
    }

    public RoleView create(UserRequests.RoleInput input) {
        Role role = new Role(UUID.randomUUID());
        apply(role, input);
        return view(roles.saveAndFlush(role));
    }

    /** Takes effect on everyone's next request. */
    public RoleView update(UUID id, UserRequests.RoleInput input) {
        roles.lockAdministrators();
        boolean managed = users.existsAdministrator();
        Role role = changeable(id);
        if (input.version() == null) {
            throw ApiException.invalid("Version is required when updating a role", Map.of("version", "required"));
        }
        if (input.version() != role.getVersion()) {
            throw ApiException.conflict("This role was changed by someone else. Reload it and try again.", Map.of());
        }
        apply(role, input);
        roles.flush();
        if (managed && !users.existsAdministrator()) {
            throw ApiException.conflict("Then nobody could manage users any more. Give someone else that first.", Map.of());
        }
        return view(role);
    }

    public void delete(UUID id) {
        roles.lockAdministrators();
        Role role = changeable(id);
        long held = roles.countUsers(id);
        if (held > 0) {
            throw ApiException.conflict(held + (held == 1 ? " user has" : " users have") + " this role. Take it from them first.", Map.of());
        }
        roles.delete(role);
    }

    private void apply(Role role, UserRequests.RoleInput input) {
        String name = input.name().trim();
        roles.findFirstByNameIgnoreCase(name).filter(other -> !other.getId().equals(role.getId())).ifPresent(other -> {
            throw ApiException.conflict("A role with this name already exists", Map.of("name", "already used by another role"));
        });
        Set<Permission> wanted = input.permissions();
        if (!wanted.contains(Permission.DESKTOP) && wanted.stream().anyMatch(p -> p.group() == Permission.Group.DESKTOP)) {
            throw ApiException.invalid("Some fields need attention",
                    Map.of("permissions", "the other desktop permissions are no use without " + Permission.DESKTOP.label().toLowerCase()));
        }
        role.setName(name);
        role.setDescription(input.description() == null || input.description().isBlank() ? null : input.description().trim());
        role.setPermissions(wanted);
    }

    private Role changeable(UUID id) {
        Role role = roles.findById(id).orElseThrow(() -> ApiException.notFound("Role not found"));
        if (role.isAdministrator()) {
            throw ApiException.conflict("The " + role.getName() + " role can't be changed.", Map.of());
        }
        return role;
    }

    private RoleView view(Role r) {
        return new RoleView(r.getId(), r.getName(), r.getDescription(), r.isAdministrator(),
                r.getPermissions().stream().sorted().toList(), roles.countUsers(r.getId()), r.getVersion());
    }
}
