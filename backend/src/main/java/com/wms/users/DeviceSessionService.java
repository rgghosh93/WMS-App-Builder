package com.wms.users;

import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wms.common.api.ApiException;
import com.wms.users.DeviceSession.Kind;

/** Signs people in and out of mobile devices. */
@Service
@Transactional
public class DeviceSessionService {

    private final SessionStore store;

    DeviceSessionService(SessionStore store) {
        this.store = store;
    }

    /** A new session: the token goes to the device and is not stored here. */
    public record SignIn(String token, DeviceUser user) {
    }

    // A wrong PIN must still be counted, so the failure doesn't roll the transaction back.
    @Transactional(noRollbackFor = ApiException.class)
    public SignIn signIn(String userCode, String pin) {
        AppUser user = store.check(userCode, pin, Kind.DEVICE);
        DeviceUser device = DeviceUser.of(user);
        if (device.permissions().isEmpty()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You don't have access to anything on devices");
        }
        return new SignIn(store.open(user, Kind.DEVICE), device);
    }

    /** The user a token belongs to, if the session is still good. */
    @Transactional(readOnly = true)
    public Optional<DeviceUser> find(String token) {
        return store.find(token, Kind.DEVICE).map(DeviceUser::of);
    }

    public void signOut(String token) {
        store.close(token, Kind.DEVICE);
    }
}
