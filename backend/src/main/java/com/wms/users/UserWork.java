package com.wms.users;

import java.util.List;
import java.util.UUID;

/** Something people work on (picking, packing, put-away). Shows what would be left behind if someone can't sign in. */
public interface UserWork {

    /** What the user has in hand, e.g. "Picking PT000009"; empty if nothing. */
    List<String> inHand(UUID userId);

    /** Whether it is started, rather than only theirs to do. */
    default boolean halfDone() {
        return true;
    }
}
