package com.wms.users;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Two demo pickers for local dev when there are no users. Their PIN is
 * {@code wms.demo-data.user-pin} in application.properties.
 */
@Component
@ConditionalOnProperty(name = "wms.demo-data.enabled", havingValue = "true")
@Order(10)
public class DemoUserLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoUserLoader.class);

    private final AppUserRepository users;
    private final PinHasher hasher;
    private final RoleRepository roles;
    private final String pin;

    public DemoUserLoader(AppUserRepository users, PinHasher hasher, RoleRepository roles, @Value("${wms.demo-data.user-pin:}") String pin) {
        this.users = users;
        this.hasher = hasher;
        this.roles = roles;
        this.pin = pin;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (pin.isBlank() || users.count() > 0) {
            return;
        }
        List<AppUser> demo = List.of(
                new AppUser(UUID.randomUUID(), "PICKER1", "Demo Picker 1", hasher.hash(pin)),
                new AppUser(UUID.randomUUID(), "PICKER2", "Demo Picker 2", hasher.hash(pin)));
        Role operator = roles.findById(Role.OPERATOR).orElseThrow();
        demo.forEach(u -> u.setRoles(List.of(operator)));
        users.saveAll(demo);
        log.info("Loaded {} demo users", demo.size());
    }
}
