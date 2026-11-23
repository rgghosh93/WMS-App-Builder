package com.wms.control;

import java.util.UUID;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.wms.control.CountViews.CountItem;
import com.wms.control.CountViews.CountState;
import com.wms.users.DeviceUser;
import com.wms.users.Permission;
import com.wms.users.Requires;

/** Counting on a mobile device. */
@RestController
@RequestMapping("/api/rf/counts")
@Requires(Permission.DEVICE_COUNT)
public class RfCountController {

    private final CountService counts;

    public RfCountController(CountService counts) {
        this.counts = counts;
    }

    public record StartRequest(String locationId, Boolean takeOver) {
    }

    @GetMapping
    public CountState state(@RequestAttribute(DeviceUser.ATTRIBUTE) DeviceUser user) {
        return counts.state(user.id());
    }

    @PostMapping("/start")
    public CountState start(@RequestAttribute(DeviceUser.ATTRIBUTE) DeviceUser user, @RequestBody StartRequest request) {
        return counts.start(Actor.of(user), request.locationId(), Boolean.TRUE.equals(request.takeOver()));
    }

    @GetMapping("/items/{code}")
    public CountItem item(@PathVariable String code, @RequestParam(required = false) String owner) {
        return counts.item(code, owner);
    }

    @PostMapping("/{id}/entries")
    public CountState enter(@RequestAttribute(DeviceUser.ATTRIBUTE) DeviceUser user, @PathVariable UUID id,
                            @RequestBody CountService.Entry entry) {
        return counts.enter(Actor.of(user), id, entry);
    }

    @DeleteMapping("/{id}/entries/{entryId}")
    public CountState remove(@RequestAttribute(DeviceUser.ATTRIBUTE) DeviceUser user, @PathVariable UUID id, @PathVariable UUID entryId) {
        return counts.removeEntry(Actor.of(user), id, entryId);
    }

    @PostMapping("/{id}/finish")
    public CountState finish(@RequestAttribute(DeviceUser.ATTRIBUTE) DeviceUser user, @PathVariable UUID id) {
        return counts.finish(Actor.of(user), id);
    }

    @PostMapping("/{id}/give-back")
    public CountState giveBack(@RequestAttribute(DeviceUser.ATTRIBUTE) DeviceUser user, @PathVariable UUID id) {
        return counts.giveBack(Actor.of(user), id);
    }
}
