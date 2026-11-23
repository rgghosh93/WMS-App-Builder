package com.wms.putaway;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.wms.putaway.PutawayViews.PutawayState;
import com.wms.users.DeviceUser;
import com.wms.users.Permission;
import com.wms.users.Requires;

/** Put-away on a mobile device. */
@RestController
@Requires(Permission.DEVICE_PUTAWAY)
@RequestMapping("/api/rf/putaway")
public class RfPutawayController {

    private final PutawayService putaway;

    public RfPutawayController(PutawayService putaway) {
        this.putaway = putaway;
    }

    public record StartRequest(String lpnId, Boolean takeOver) {
    }

    public record RedirectRequest(String locationId, OverrideReason reason) {
    }

    @GetMapping
    public PutawayState state(@RequestAttribute(DeviceUser.ATTRIBUTE) DeviceUser user) {
        return putaway.state(user.id());
    }

    @PostMapping("/start")
    public PutawayState start(@RequestAttribute(DeviceUser.ATTRIBUTE) DeviceUser user, @RequestBody StartRequest request) {
        return putaway.start(user.id(), request.lpnId(), Boolean.TRUE.equals(request.takeOver()));
    }

    @PostMapping("/tasks/{id}/redirect")
    public PutawayState redirect(@RequestAttribute(DeviceUser.ATTRIBUTE) DeviceUser user, @PathVariable UUID id,
                                 @RequestBody RedirectRequest request) {
        return putaway.redirect(user.id(), id, request.locationId(), request.reason());
    }

    @PostMapping("/tasks/{id}/confirm")
    public PutawayState confirm(@RequestAttribute(DeviceUser.ATTRIBUTE) DeviceUser user, @PathVariable UUID id,
                                @RequestBody PutawayService.Confirm request) {
        return putaway.confirm(user.id(), id, request);
    }

    @PostMapping("/tasks/{id}/release")
    public PutawayState release(@RequestAttribute(DeviceUser.ATTRIBUTE) DeviceUser user, @PathVariable UUID id) {
        return putaway.release(user.id(), id);
    }
}
