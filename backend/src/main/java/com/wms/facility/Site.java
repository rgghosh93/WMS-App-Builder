package com.wms.facility;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * The facility the current thread works in. Everything read and written belongs to it; see {@link FacilityRecord}.
 * Requests are in the facility of whoever is signed in. Anything else, such as starting up, is in the first facility
 * unless it says otherwise.
 * <p>
 * It counts from when a transaction begins, so it has to be set before.
 */
public final class Site {

    /** The facility every installation starts with. */
    public static final UUID FIRST = UUID.fromString("00000000-0000-4000-8002-000000000001");

    /** Every facility at once, to look across them. */
    static final UUID EVERYWHERE = new UUID(0, 0);

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private Site() {
    }

    public static UUID current() {
        UUID id = CURRENT.get();
        return id == null ? FIRST : id;
    }

    public static void enter(UUID facilityId) {
        CURRENT.set(facilityId);
    }

    public static void leave() {
        CURRENT.remove();
    }

    public static <T> T in(UUID facilityId, Supplier<T> work) {
        UUID before = CURRENT.get();
        CURRENT.set(facilityId);
        try {
            return work.get();
        } finally {
            if (before == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(before);
            }
        }
    }

    /** For looking only: whoever asks must leave out facilities the person may not see. */
    public static <T> T everywhere(Supplier<T> work) {
        return in(EVERYWHERE, work);
    }
}
