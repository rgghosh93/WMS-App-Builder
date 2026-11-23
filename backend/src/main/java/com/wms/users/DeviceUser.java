package com.wms.users;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.wms.facility.FacilityRef;

/**
 * The person signed in on the device making a request.
 *
 * @param permissions what they may do on devices
 * @param facility    where they are working
 * @param facilities  everywhere they may
 */
public record DeviceUser(UUID id, String userCode, String displayName, Set<Permission> permissions, FacilityRef facility,
                         List<FacilityRef> facilities) {

    /** Request attribute holding the signed-in user on /api/rf requests. */
    public static final String ATTRIBUTE = "wms.deviceUser";

    static DeviceUser of(SessionStore.Found found) {
        AppUser user = found.user();
        return new DeviceUser(user.getId(), user.getUserCode(), user.getDisplayName(), user.permissions().stream()
                .filter(p -> p.group() == Permission.Group.DEVICE).collect(Collectors.toUnmodifiableSet()), found.facility(), found.facilities());
    }
}
