package com.wms.users;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The person signed in on the device making a request.
 *
 * @param permissions what they may do on devices
 */
public record DeviceUser(UUID id, String userCode, String displayName, Set<Permission> permissions) {

    /** Request attribute holding the signed-in user on /api/rf requests. */
    public static final String ATTRIBUTE = "wms.deviceUser";

    static DeviceUser of(AppUser user) {
        return new DeviceUser(user.getId(), user.getUserCode(), user.getDisplayName(), user.permissions().stream()
                .filter(p -> p.group() == Permission.Group.DEVICE).collect(Collectors.toUnmodifiableSet()));
    }
}
