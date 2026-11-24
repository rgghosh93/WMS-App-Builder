package com.wms.users;

/** Something a role lets people do. Seeing things on the desktop only takes DESKTOP, except users and roles. */
public enum Permission {
    DESKTOP(Group.DESKTOP, "Use the desktop", "Sign in to the desktop and see everything on it but users and roles"),
    MASTER_DATA(Group.DESKTOP, "Change master data", "Locations, items, containers, units of measure and carriers"),
    INBOUND(Group.DESKTOP, "Create and cancel ASNs", ""),
    PUTAWAY_RULES(Group.DESKTOP, "Change put-away rules", ""),
    REPLENISHMENT(Group.DESKTOP, "Manage replenishment", "Rules, running them, and cancelling tasks"),
    TASKS(Group.DESKTOP, "Manage the task queue", "Priorities, who does what, and how kinds of work are treated"),
    ORDERS(Group.DESKTOP, "Manage orders", "Create, allocate, cancel and close short"),
    SHIPPING(Group.DESKTOP, "Prepare shipments", "Addresses, carrier services and tracking numbers"),
    INVENTORY(Group.DESKTOP, "Control inventory", "Adjust and hold stock, and plan counts; larger changes wait for approval"),
    INVENTORY_APPROVE(Group.DESKTOP, "Approve inventory changes", "Other people's larger adjustments and count differences, the limit, and reason codes"),
    FACILITIES(Group.DESKTOP, "Manage facilities", "Add and change them; what is in them is up to the other permissions"),
    USERS(Group.DESKTOP, "Manage users and roles", "Can give anyone any permission, themselves included"),
    APP_BUILDER(Group.DESKTOP, "Use the App Builder", "Describe changes to the WMS and have the agent build them, where the App Builder is on"),
    APP_BUILDER_DEVELOPER(Group.DESKTOP, "See how the App Builder works",
            "Developer details: the code the agent reads and changes, its test results, and where its work is kept"),
    DEVICE_NEXT(Group.DEVICE, "Get the next task", "Offered the most urgent task nearby, of the kinds they may do"),
    DEVICE_RECEIVE(Group.DEVICE, "Receive", ""),
    DEVICE_PUTAWAY(Group.DEVICE, "Put away", ""),
    DEVICE_REPLENISH(Group.DEVICE, "Replenish", ""),
    DEVICE_MOVE(Group.DEVICE, "Move stock", ""),
    DEVICE_COUNT(Group.DEVICE, "Count", ""),
    DEVICE_PICK(Group.DEVICE, "Pick", ""),
    DEVICE_PACK(Group.DEVICE, "Pack", "At a pack station"),
    DEVICE_LOAD(Group.DEVICE, "Load and ship", "");

    /** Where a permission counts. */
    public enum Group { DESKTOP, DEVICE }

    private final Group group;
    private final String label;
    private final String description;

    Permission(Group group, String label, String description) {
        this.group = group;
        this.label = label;
        this.description = description;
    }

    public Group group() { return group; }
    public String label() { return label; }
    public String description() { return description; }
}
