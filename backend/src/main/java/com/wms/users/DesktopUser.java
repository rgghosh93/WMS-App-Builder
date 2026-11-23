package com.wms.users;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The person signed in on the desktop making a request.
 *
 * @param permissions        what they may do there
 * @param mustChangePassword they have to choose a password of their own before anything else
 */
public record DesktopUser(UUID id, String userCode, String displayName, Set<Permission> permissions, boolean mustChangePassword) {

    /** Request attribute holding the signed-in user on desktop requests. */
    public static final String ATTRIBUTE = "wms.desktopUser";

    static DesktopUser of(AppUser user) {
        return new DesktopUser(user.getId(), user.getUserCode(), user.getDisplayName(), user.permissions().stream()
                .filter(p -> p.group() == Permission.Group.DESKTOP).collect(Collectors.toUnmodifiableSet()), user.isMustChangePassword());
    }
}
