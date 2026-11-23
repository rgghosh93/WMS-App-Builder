package com.wms.replenishment;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.wms.replenishment.ReplenishmentViews.DeviceState;
import com.wms.users.DeviceUser;
import com.wms.users.Permission;
import com.wms.users.Requires;

/** Replenishment on a mobile device. */
@RestController
@Requires(Permission.DEVICE_REPLENISH)
@RequestMapping("/api/rf/replenishment")
public class RfReplenishmentController {

    private final ReplenishmentService replenishment;
    private final ReplenishmentRun run;

    public RfReplenishmentController(ReplenishmentService replenishment, ReplenishmentRun run) {
        this.replenishment = replenishment;
        this.run = run;
    }

    /** @param taskId the most urgent task if left out */
    public record StartRequest(UUID taskId) {
    }

    @GetMapping
    public DeviceState state(@RequestAttribute(DeviceUser.ATTRIBUTE) DeviceUser user) {
        return replenishment.state(user.id());
    }

    @PostMapping("/start")
    public DeviceState start(@RequestAttribute(DeviceUser.ATTRIBUTE) DeviceUser user, @RequestBody StartRequest request) {
        return replenishment.start(user.id(), request.taskId());
    }

    @PostMapping("/tasks/{id}/confirm")
    public DeviceState confirm(@RequestAttribute(DeviceUser.ATTRIBUTE) DeviceUser user, @PathVariable UUID id,
                               @RequestBody ReplenishmentService.Confirm request) {
        UUID itemId = replenishment.confirm(user.id(), id, request);
        if (itemId != null) {
            run.afterMove(itemId);
        }
        return replenishment.state(user.id());
    }

    @PostMapping("/tasks/{id}/release")
    public DeviceState release(@RequestAttribute(DeviceUser.ATTRIBUTE) DeviceUser user, @PathVariable UUID id) {
        return replenishment.release(user.id(), id);
    }
}
