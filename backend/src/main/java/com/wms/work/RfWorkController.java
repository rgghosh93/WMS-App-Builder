package com.wms.work;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.wms.users.DeviceUser;
import com.wms.users.Permission;
import com.wms.users.Requires;
import com.wms.work.WorkViews.Next;

/** Getting the next task on a mobile device. */
@RestController
@Requires(Permission.DEVICE_NEXT)
@RequestMapping("/api/rf/work")
public class RfWorkController {

    private final NextService next;

    public RfWorkController(NextService next) {
        this.next = next;
    }

    /** @param skip tasks not wanted now */
    public record NextRequest(List<UUID> skip) {
    }

    @PostMapping("/next")
    public Next next(@RequestAttribute(DeviceUser.ATTRIBUTE) DeviceUser user, @RequestBody NextRequest request) {
        return next.next(user, request.skip());
    }
}
