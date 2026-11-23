package com.wms.control;

import java.util.UUID;

import com.wms.inventory.StockLedger.Note;
import com.wms.users.DesktopUser;
import com.wms.users.DeviceUser;

/** Who is changing stock. */
public record Actor(UUID id, String name) {

    static Actor of(DesktopUser user) {
        return new Actor(user.id(), user.displayName());
    }

    static Actor of(DeviceUser user) {
        return new Actor(user.id(), user.displayName());
    }

    Note note(String reference) {
        return Note.by(id, name, reference);
    }
}
