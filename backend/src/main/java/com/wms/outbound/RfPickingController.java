package com.wms.outbound;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.wms.outbound.PickingViews.PickZone;
import com.wms.outbound.PickingViews.PickingState;
import com.wms.users.DeviceUser;
import com.wms.users.Permission;
import com.wms.users.Requires;

/** Picking on a mobile device. Every call returns what the picker has to do next. */
@RestController
@Requires(Permission.DEVICE_PICK)
@RequestMapping("/api/rf/picking")
public class RfPickingController {

    private final PickingService picking;

    public RfPickingController(PickingService picking) {
        this.picking = picking;
    }

    /** @param zoneId only tasks in this pick zone
     *  @param taskId this task and no other
     *  @param takeOver carry on with the task someone else is picking into this container */
    public record StartRequest(String lpnId, UUID containerTypeId, Boolean takeOver, UUID zoneId, UUID taskId) {
    }

    public record DropRequest(String locationId) {
    }

    @GetMapping
    public PickingState state(@RequestAttribute(DeviceUser.ATTRIBUTE) DeviceUser user) {
        return picking.state(user.id());
    }

    @GetMapping("/zones")
    public List<PickZone> zones() {
        return picking.zones();
    }

    @PostMapping("/start")
    public PickingState start(@RequestAttribute(DeviceUser.ATTRIBUTE) DeviceUser user, @RequestBody StartRequest request) {
        return picking.start(user.id(), request.lpnId(), request.containerTypeId(), Boolean.TRUE.equals(request.takeOver()), request.zoneId(), request.taskId());
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
