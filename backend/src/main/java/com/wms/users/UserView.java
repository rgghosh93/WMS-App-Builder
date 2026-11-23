package com.wms.users;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.wms.common.ActiveStatus;
import com.wms.facility.FacilityRef;

/**
 * A user, without anything secret.
 *
 * @param facilities  where they work; everywhere if empty
 * @param hasPin      can sign in to devices, if a role allows it
 * @param hasPassword can sign in to the desktop, if a role allows it
 * @param lockedUntil set while too many wrong PINs or passwords keep them out
 * @param devices     places they are signed in
 * @param inHand      what they are in the middle of; only when one user is looked at
 */
public record UserView(UUID id, String userCode, String displayName, ActiveStatus status, List<RoleRef> roles,
                       List<FacilityRef> facilities, boolean hasPin,
                       boolean hasPassword, Instant lockedUntil, long devices, Instant lastSignInAt, List<String> inHand, long version) {

    public record RoleRef(UUID id, String name) {
    }

    static UserView of(AppUser u, long devices, List<String> inHand, Instant now) {
        return new UserView(u.getId(), u.getUserCode(), u.getDisplayName(), u.getStatus(),
                u.getRoles().stream().map(r -> new RoleRef(r.getId(), r.getName())).sorted(Comparator.comparing(RoleRef::name)).toList(),
                u.getFacilities().stream().map(FacilityRef::of).sorted(Comparator.comparing(FacilityRef::code)).toList(),
                u.getPinHash() != null, u.getPasswordHash() != null,
                u.isLockedAt(now) ? u.getLockedUntil() : null, devices, u.getLastSignInAt(), inHand, u.getVersion());
    }
}
