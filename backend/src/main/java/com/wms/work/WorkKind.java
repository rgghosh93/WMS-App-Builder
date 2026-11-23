package com.wms.work;

import com.wms.users.Permission;

/** Kinds of tasks in the queue. */
public enum WorkKind {
    PUTAWAY("Put-away", Permission.DEVICE_PUTAWAY, true),
    REPLENISHMENT("Replenishment", Permission.DEVICE_REPLENISH, true),
    COUNT("Count", Permission.DEVICE_COUNT, true),
    PICK("Pick", Permission.DEVICE_PICK, true),
    /** Done at the station its container was dropped at, by whoever works there. */
    PACK("Pack", Permission.DEVICE_PACK, false);

    private final String label;
    private final Permission permission;
    private final boolean assignable;

    WorkKind(String label, Permission permission, boolean assignable) {
        this.label = label;
        this.permission = permission;
        this.assignable = assignable;
    }

    public String label() { return label; }
    /** What it takes to do it. */
    public Permission permission() { return permission; }
    public boolean isAssignable() { return assignable; }
    /** Picks and packs are as urgent as their orders. The rest have a priority of their own. */
    public boolean isForOrders() { return this == PICK || this == PACK; }
}
