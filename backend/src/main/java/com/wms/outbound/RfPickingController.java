package com.wms.outbound;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.wms.outbound.PickingViews.PickingState;
import com.wms.users.DeviceUser;

/** Picking on a mobile device. Every call returns what the picker has to do next. */
@RestController
@RequestMapping("/api/rf/picking")
public class RfPickingController {

    private final PickingService picking;

    public RfPickingController(PickingService picking) {
        this.picking = picking;
    }

    public record StartRequest(String lpnId, UUID containerTypeId) {
    }

    public record DropRequest(String locationId) {
    }

    @GetMapping
    public PickingState state(@RequestAttribute(DeviceUser.ATTRIBUTE) DeviceUser user) {
        return picking.state(user.id());
    }

    @PostMapping("/start")
    public PickingState start(@RequestAttribute(DeviceUser.ATTRIBUTE) DeviceUser user, @RequestBody StartRequest request) {
        return picking.start(user.id(), request.lpnId(), request.containerTypeId());
    }

    @PostMapping("/confirm")
    public PickingState confirm(@RequestAttribute(DeviceUser.ATTRIBUTE) DeviceUser user,
                                @RequestBody PickingService.Confirm request) {
        return picking.confirm(user.id(), request);
    }

    @PostMapping("/drop")
    public PickingState drop(@RequestAttribute(DeviceUser.ATTRIBUTE) DeviceUser user, @RequestBody DropRequest request) {
        return picking.drop(user.id(), request.locationId());
    }

    @PostMapping("/release")
    public PickingState release(@RequestAttribute(DeviceUser.ATTRIBUTE) DeviceUser user) {
        return picking.release(user.id());
    }
}
