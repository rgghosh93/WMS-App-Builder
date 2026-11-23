package com.wms.work;

import java.time.Instant;
import java.util.UUID;

import com.wms.masterdata.location.Location;

/**
 * A task that isn't finished.
 *
 * @param what      what is to be done, in short
 * @param at        where it starts
 * @param lpn       what to scan to start it, if it is one
 * @param ready     whether it can be started now
 * @param userId    who is doing it, if it is started
 * @param priority  how urgent it is of itself, 1 to 9
 * @param link      its page on the desktop
 */
public record WorkItem(WorkKind kind, UUID id, String number, String what, Location at, String lpn, boolean ready, UUID userId,
                       String userName, int priority, Instant createdAt, Instant startedAt, String link) {

    public boolean started() {
        return userId != null;
    }
}
