package com.wms.users;

import java.util.UUID;

/** The person signed in on the device making a request. */
public record DeviceUser(UUID id, String userCode, String displayName) {

    /** Request attribute holding the signed-in user on /api/rf requests. */
    public static final String ATTRIBUTE = "wms.deviceUser";

    static DeviceUser of(AppUser user) {
        return new DeviceUser(user.getId(), user.getUserCode(), user.getDisplayName());
    }
}
