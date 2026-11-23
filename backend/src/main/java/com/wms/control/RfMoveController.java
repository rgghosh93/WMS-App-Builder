package com.wms.control;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.wms.control.ControlViews.MoveResult;
import com.wms.control.ControlViews.MoveSource;
import com.wms.users.DeviceUser;
import com.wms.users.Permission;
import com.wms.users.Requires;

/** Moving stock on a mobile device. */
@RestController
@RequestMapping("/api/rf/moves")
@Requires(Permission.DEVICE_MOVE)
public class RfMoveController {

    private final MoveService moves;

    public RfMoveController(MoveService moves) {
        this.moves = moves;
    }

    @GetMapping("/from/{code}")
    public MoveSource source(@PathVariable String code) {
        return moves.source(code);
    }

    @PostMapping("/lpn")
    public MoveResult lpn(@RequestAttribute(DeviceUser.ATTRIBUTE) DeviceUser user, @RequestBody MoveService.MoveLpn request) {
        return moves.lpn(Actor.of(user), request);
    }

    @PostMapping("/loose")
    public MoveResult loose(@RequestAttribute(DeviceUser.ATTRIBUTE) DeviceUser user, @RequestBody MoveService.MoveLoose request) {
        return moves.loose(Actor.of(user), request);
    }
}
