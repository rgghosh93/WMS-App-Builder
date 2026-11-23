package com.wms.users;

import java.util.Optional;
import java.util.UUID;

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
        DeviceUser device = DeviceUser.of(store.place(user, null));
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

    /** Goes on working in another facility, once nothing is left half done in this one. */
    public DeviceUser go(String token, UUID facilityId) {
        DeviceUser now = find(token).orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Sign in to continue"));
        if (now.facility().id().equals(facilityId)) {
            return now;
        }
        return DeviceUser.of(store.go(token, Kind.DEVICE, facilityId));
    }

    public void signOut(String token) {
        store.close(token, Kind.DEVICE);
    }
}
