package com.wms.users;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.wms.facility.FacilityRef;
import com.wms.facility.Site;

/**
 * The person signed in on the desktop making a request.
 *
 * @param permissions        what they may do there
 * @param mustChangePassword they have to choose a password of their own before anything else
 * @param facility           where they are working
 * @param facilities         everywhere they may
 */
public record DesktopUser(UUID id, String userCode, String displayName, Set<Permission> permissions, boolean mustChangePassword,
                          FacilityRef facility, List<FacilityRef> facilities) {

    /** In the first facility. */
    public DesktopUser(UUID id, String userCode, String displayName, Set<Permission> permissions, boolean mustChangePassword) {
        this(id, userCode, displayName, permissions, mustChangePassword, new FacilityRef(Site.FIRST, "MAIN", "Main warehouse"), List.of());
    }

    /** Request attribute holding the signed-in user on desktop requests. */
    public static final String ATTRIBUTE = "wms.desktopUser";

    static DesktopUser of(SessionStore.Found found) {
        AppUser user = found.user();
        return new DesktopUser(user.getId(), user.getUserCode(), user.getDisplayName(), user.permissions().stream()
                .filter(p -> p.group() == Permission.Group.DESKTOP).collect(Collectors.toUnmodifiableSet()), user.isMustChangePassword(),
                found.facility(), found.facilities());
    }
}
