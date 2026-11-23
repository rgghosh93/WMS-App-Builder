package com.wms.users;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wms.common.ActiveStatus;
import com.wms.common.Codes;
import com.wms.common.api.ApiException;

/** Who can sign in, how, and with which roles. */
@Service
@Transactional
public class UserService {

    static final int MIN_PIN = 4;
    static final int MAX_PIN = 12;

    private final AppUserRepository users;
    private final DeviceSessionRepository sessions;
    private final PinHasher hasher;
    private final RoleRepository roles;
    private final List<UserWork> work;

    UserService(AppUserRepository users, DeviceSessionRepository sessions, PinHasher hasher, RoleRepository roles,
                List<UserWork> work) {
        this.users = users;
        this.sessions = sessions;
        this.hasher = hasher;
        this.roles = roles;
        this.work = work;
    }

    @Transactional(readOnly = true)
    public Page<UserView> search(UserSearch search, Pageable pageable) {
        Page<AppUser> page = users.findAll(search.toSpecification(), pageable);
        Instant now = Instant.now();
        Map<UUID, Long> devices = devices(page.getContent().stream().map(AppUser::getId).toList(), now);
        return page.map(u -> UserView.of(u, devices.getOrDefault(u.getId(), 0L), List.of(), now));
    }

    @Transactional(readOnly = true)
    public UserView get(UUID id) {
        return view(users.findById(id).orElseThrow(() -> ApiException.notFound("User not found")));
    }

    public UserView create(UserRequests.Create request) {
        String code = checkedCode(request.userCode(), null);
        if (blank(request.pin()) && blank(request.password())) {
            throw ApiException.invalid("Give them a PIN, a password or both", Map.of("pin", "give a PIN or a password"));
        }
        AppUser user = new AppUser(UUID.randomUUID(), code, request.displayName().trim(),
                blank(request.pin()) ? null : hasher.hash(checkedPin(request.pin())));
        if (!blank(request.password())) {
            Passwords.check(request.password(), code, "password");
            user.setPassword(hasher.hashPassword(request.password()), true);
        }
        user.setStatus(request.status());
        user.setRoles(checkedRoles(request.roleIds()));
        return view(users.saveAndFlush(user));
    }

    /** Making someone inactive also signs them out everywhere. Roles stay as they are if left out. */
    public UserView update(UUID id, UserRequests.Update request) {
        roles.lockAdministrators();
        boolean managed = users.existsAdministrator();
        AppUser user = lock(id);
        if (request.version() == null) {
            throw ApiException.invalid("Version is required when updating a user", Map.of("version", "required"));
        }
        if (request.version() != user.getVersion()) {
            throw ApiException.conflict("This user was changed by someone else. Reload it and try again.", Map.of());
        }
        user.setUserCode(checkedCode(request.userCode(), id));
        user.setDisplayName(request.displayName().trim());
        user.setStatus(request.status());
        if (request.roleIds() != null) {
            user.setRoles(checkedRoles(request.roleIds()));
        }
        users.flush();
        keepAnAdministrator(managed);
        if (request.status() == ActiveStatus.INACTIVE) {
            sessions.deleteByUser(id);
        }
        return view(user);
    }

    /** Gives a new PIN. The old one stops working at once, so every device is signed out and a lockout ends. */
    public UserView setPin(UUID id, String pin) {
        AppUser user = lock(id);
        user.setPin(hasher.hash(checkedPin(pin)));
        users.flush();
        sessions.deleteByUser(id);
        return view(user);
    }

    /** Gives a password to start with: they have to choose their own the first time. Signs them out everywhere. */
    public UserView setPassword(UUID id, String password) {
        AppUser user = lock(id);
        Passwords.check(password, user.getUserCode(), "password");
        user.setPassword(hasher.hashPassword(password), true);
        users.flush();
        sessions.deleteByUser(id);
        return view(user);
    }

    public UserView unlock(UUID id) {
        AppUser user = lock(id);
        user.unlock();
        users.flush();
        return view(user);
    }

    public UserView signOut(UUID id) {
        AppUser user = lock(id);
        sessions.deleteByUser(id);
        return view(user);
    }

    /** Only someone who has never done anything; everyone else stays, so the records still say who did what. */
    public void delete(UUID id) {
        roles.lockAdministrators();
        boolean managed = users.existsAdministrator();
        AppUser user = lock(id);
        sessions.deleteByUser(id);
        try {
            users.delete(user);
            users.flush();
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict(user.getUserCode() + " has worked in the warehouse and can't be deleted. Mark them Inactive instead.",
                    Map.of());
        }
        keepAnAdministrator(managed);
    }

    /** @param before whether someone could manage users before this change */
    private void keepAnAdministrator(boolean before) {
        if (before && !users.existsAdministrator()) {
            throw ApiException.conflict("Then nobody could manage users any more. Give someone else that first.", Map.of());
        }
    }

    private Collection<Role> checkedRoles(Set<UUID> ids) {
        List<Role> found = ids == null ? List.of() : roles.findAllById(ids);
        if (ids != null && found.size() != ids.size()) {
            throw ApiException.invalid("A role no longer exists. Reload and try again.", Map.of("roleIds", "role not found"));
        }
        return found;
    }

    private static boolean blank(String text) {
        return text == null || text.isEmpty();
    }

    private String checkedCode(String userCode, UUID self) {
        String code = Codes.normalize(userCode);
        users.findByUserCode(code).filter(other -> !other.getId().equals(self)).ifPresent(other -> {
            throw ApiException.conflict("A user with this ID already exists", Map.of("userCode", "already used by another user"));
        });
        return code;
    }

    private static String checkedPin(String pin) {
        String problem = null;
        if (pin == null || pin.isEmpty()) {
            problem = "enter a PIN";
        } else if (!pin.matches("[0-9]{" + MIN_PIN + "," + MAX_PIN + "}")) {
            problem = "use " + MIN_PIN + " to " + MAX_PIN + " digits";
        } else if (isPattern(pin)) {
            problem = "too easy to guess: don't repeat one digit or count up or down";
        }
        if (problem != null) {
            throw ApiException.invalid("That PIN can't be used", Map.of("pin", problem));
        }
        return pin;
    }

    /** 1111, 1234 or 9876: every step from one digit to the next is the same, and at most one. */
    private static boolean isPattern(String pin) {
        int step = pin.charAt(1) - pin.charAt(0);
        if (Math.abs(step) > 1) {
            return false;
        }
        for (int i = 2; i < pin.length(); i++) {
            if (pin.charAt(i) - pin.charAt(i - 1) != step) {
                return false;
            }
        }
        return true;
    }

    private AppUser lock(UUID id) {
        return users.findLocked(id).orElseThrow(() -> ApiException.notFound("User not found"));
    }

    private UserView view(AppUser user) {
        Instant now = Instant.now();
        return UserView.of(user, devices(List.of(user.getId()), now).getOrDefault(user.getId(), 0L),
                work.stream().flatMap(w -> w.inHand(user.getId()).stream()).toList(), now);
    }

    private Map<UUID, Long> devices(Collection<UUID> ids, Instant now) {
        Map<UUID, Long> counts = new HashMap<>();
        if (!ids.isEmpty()) {
            sessions.countByUser(ids, now).forEach(row -> counts.put((UUID) row[0], (Long) row[1]));
        }
        return counts;
    }
}
