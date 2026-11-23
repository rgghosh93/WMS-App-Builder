package com.wms.users;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wms.common.Codes;
import com.wms.common.api.ApiException;
import com.wms.users.DeviceSession.Kind;

/** Signs people in and out of the desktop, and makes the first administrator. */
@Service
@Transactional
public class DesktopSessionService {

    private final SessionStore store;
    private final AppUserRepository users;
    private final RoleRepository roles;
    private final PinHasher hasher;
    /** Lets a new administrator be made although there is one, for when nobody knows their password. */
    private final boolean allowSetup;

    DesktopSessionService(SessionStore store, AppUserRepository users, RoleRepository roles, PinHasher hasher,
                          @Value("${wms.users.allow-setup:false}") boolean allowSetup) {
        this.store = store;
        this.users = users;
        this.roles = roles;
        this.hasher = hasher;
        this.allowSetup = allowSetup;
    }

    public record SignIn(String token, DesktopUser user) {
    }

    /** Whether the first administrator still has to be made. */
    @Transactional(readOnly = true)
    public boolean needsSetup() {
        return allowSetup || !users.existsAdministrator();
    }

    /** Makes an administrator and signs them in. The caller makes sure this comes from this computer. */
    public SignIn setUp(String userCode, String displayName, String password) {
        roles.lockAdministrators(); // only one at a time
        if (!needsSetup()) {
            throw ApiException.conflict("There is already an administrator. Sign in instead.", Map.of());
        }
        String code = Codes.normalize(userCode);
        if (users.findByUserCode(code).isPresent()) {
            throw ApiException.conflict("A user with this ID already exists", Map.of("userCode", "already used by another user"));
        }
        Passwords.check(password, code, "password");
        AppUser user = new AppUser(UUID.randomUUID(), code, displayName.trim(), null);
        user.setPassword(hasher.hashPassword(password), false);
        user.setRoles(List.of(roles.findById(Role.ADMINISTRATOR).orElseThrow()));
        user = users.saveAndFlush(user);
        return new SignIn(store.open(user, Kind.DESKTOP), DesktopUser.of(store.place(user, null)));
    }

    // A wrong password must still be counted, so the failure doesn't roll the transaction back.
    @Transactional(noRollbackFor = ApiException.class)
    public SignIn signIn(String userCode, String password) {
        AppUser user = store.check(userCode, password, Kind.DESKTOP);
        DesktopUser desktop = DesktopUser.of(store.place(user, null));
        if (!desktop.permissions().contains(Permission.DESKTOP)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You don't have access to the desktop");
        }
        return new SignIn(store.open(user, Kind.DESKTOP), desktop);
    }

    /** Who a token belongs to, if the session is still good and they may still use the desktop. */
    @Transactional(readOnly = true)
    public Optional<DesktopUser> find(String token) {
        return store.find(token, Kind.DESKTOP).map(DesktopUser::of).filter(u -> u.permissions().contains(Permission.DESKTOP));
    }

    /** Goes on working in another facility. */
    public DesktopUser go(String token, UUID facilityId) {
        if (find(token).isEmpty()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Sign in to continue");
        }
        return DesktopUser.of(store.go(token, Kind.DESKTOP, facilityId));
    }

    public void signOut(String token) {
        store.close(token, Kind.DESKTOP);
    }

    /** Someone chooses their own new password. They are signed out everywhere else. */
    @Transactional(noRollbackFor = ApiException.class)
    public DesktopUser changePassword(String token, String current, String next) {
        DesktopUser signedIn = find(token).orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Sign in to continue"));
        AppUser user;
        try {
            user = store.check(signedIn.userCode(), current, Kind.DESKTOP);
        } catch (ApiException e) {
            if (e.status() == HttpStatus.UNAUTHORIZED) {
                throw ApiException.invalid("That isn't your password", Map.of("currentPassword", "not your password"));
            }
            throw e;
        }
        if (current.equals(next)) {
            throw ApiException.invalid("That password can't be used", Map.of("newPassword", "choose a different one"));
        }
        Passwords.check(next, user.getUserCode(), "newPassword");
        user.setPassword(hasher.hashPassword(next), false);
        users.flush();
        store.closeOthers(user.getId(), token);
        return DesktopUser.of(store.place(user, signedIn.facility().id()));
    }
}
