package com.wms.outbound;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.wms.masterdata.carrier.CarrierResponse;
import com.wms.masterdata.carrier.CarriersService;
import com.wms.outbound.ShippingViews.DoorState;
import com.wms.outbound.ShippingViews.DoorView;
import com.wms.outbound.ShippingViews.ManifestView;
import com.wms.users.DeviceUser;
import com.wms.users.Permission;
import com.wms.users.Requires;

/** Loading cartons at a dock door on a mobile device, and ship confirm. */
@RestController
@Requires(Permission.DEVICE_LOAD)
@RequestMapping("/api/rf/loading")
public class RfLoadingController {

    private final ShippingService shipping;
    private final CarriersService carriers;

    public RfLoadingController(ShippingService shipping, CarriersService carriers) {
        this.shipping = shipping;
        this.carriers = carriers;
    }

    public record OpenRequest(UUID carrierId) {
    }

    public record CartonScan(String lpnId) {
    }

    public record CloseRequest(String trailerNumber, String sealNumber, Boolean leaveBehind) {
    }

    @GetMapping("/doors")
    public List<DoorView> doors() {
        return shipping.doors();
    }

    @GetMapping("/carriers")
    public List<CarrierResponse> carriers() {
        return carriers.list();
    }

    @GetMapping("/doors/{locationId}")
    public DoorState door(@PathVariable String locationId) {
        return shipping.door(locationId);
    }

    @PostMapping("/doors/{locationId}/manifest")
    public DoorState open(@RequestAttribute(DeviceUser.ATTRIBUTE) DeviceUser user, @PathVariable String locationId,
                          @RequestBody OpenRequest request) {
        return shipping.openManifest(user.id(), locationId, request.carrierId());
    }

    @PostMapping("/manifests/{id}/load")
    public DoorState load(@RequestAttribute(DeviceUser.ATTRIBUTE) DeviceUser user, @PathVariable UUID id,
                          @RequestBody CartonScan scan) {
        return shipping.load(user.id(), id, scan.lpnId());
    }

    @PostMapping("/manifests/{id}/unload")
    public DoorState unload(@PathVariable UUID id, @RequestBody CartonScan scan) {
        return shipping.unload(id, scan.lpnId());
    }

    @PostMapping("/manifests/{id}/discard")
    public DoorState discard(@PathVariable UUID id) {
        return shipping.discard(id);
    }

    @PostMapping("/manifests/{id}/close")
    public ManifestView close(@RequestAttribute(DeviceUser.ATTRIBUTE) DeviceUser user, @PathVariable UUID id,
                              @RequestBody CloseRequest request) {
        return shipping.close(user.id(), id, request.trailerNumber(), request.sealNumber(),
                Boolean.TRUE.equals(request.leaveBehind()));
    }
}
