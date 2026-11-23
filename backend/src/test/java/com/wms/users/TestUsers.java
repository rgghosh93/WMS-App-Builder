package com.wms.users;

import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.stereotype.Component;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.ConfigurableMockMvcBuilder;

/**
 * People for tests. MockMvc requests come from someone signed in to the desktop who may do everything
 * there, so tests that aren't about signing in don't have to; device requests still need a session.
 */
@Component
public class TestUsers implements MockMvcBuilderCustomizer {

    public static final DesktopUser ADMIN = new DesktopUser(UUID.randomUUID(), "TEST", "Test Admin", EnumSet.allOf(Permission.class), false);

    private final AppUserRepository users;
    private final RoleRepository roles;
    private final PinHasher hasher;

    TestUsers(AppUserRepository users, RoleRepository roles, PinHasher hasher) {
        this.users = users;
        this.roles = roles;
        this.hasher = hasher;
    }

    @Override
    public void customize(ConfigurableMockMvcBuilder<?> builder) {
        builder.defaultRequest(MockMvcRequestBuilders.get("/").requestAttr(DesktopUser.ATTRIBUTE, ADMIN));
    }

    public UUID idOf(String code) {
        return users.findByUserCode(code).orElseThrow().getId();
    }

    /** Someone who may do everything on devices. */
    public AppUser operator(String code, String name, String pin) {
        AppUser user = new AppUser(UUID.randomUUID(), code, name, hasher.hash(pin));
        user.setRoles(List.of(roles.findById(Role.OPERATOR).orElseThrow()));
        return users.save(user);
    }
}
