package com.wms.outbound;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.wms.common.measure.WeightUom;
import com.wms.outbound.PackingViews.PackTaskView;
import com.wms.outbound.PackingViews.StationQueue;
import com.wms.outbound.PackingViews.StationView;
import com.wms.outbound.PickingViews.ContainerTypeOption;
import com.wms.users.DeviceUser;
import com.wms.users.Permission;
import com.wms.users.Requires;

/** Packing at a pack station, for a signed-in packer. */
@RestController
@Requires(Permission.DEVICE_PACK)
@RequestMapping("/api/rf/packing")
public class RfPackingController {

    private final PackingService packing;

    public RfPackingController(PackingService packing) {
        this.packing = packing;
    }

    public record StartRequest(String locationId, String lpnId, Boolean takeOver) {
    }

    /** @param lpnId the carton's label, or blank to have an LPN made */
    public record CartonRequest(UUID orderId, UUID containerTypeId, String lpnId) {
    }

    public record CloseRequest(BigDecimal weight, WeightUom weightUom) {
    }

    /** @param remaining what the station was showing as left to pack */
    public record MissingRequest(UUID detailId, Integer remaining) {
    }

    @GetMapping("/stations")
    public List<StationView> stations() {
        return packing.stations();
    }

    @GetMapping("/carton-types")
    public List<ContainerTypeOption> cartonTypes() {
        return packing.cartonTypes();
    }

    @GetMapping("/stations/{locationId}")
    public StationQueue queue(@PathVariable String locationId) {
        return packing.queue(locationId);
    }

    @PostMapping("/start")
    public PackTaskView start(@RequestAttribute(DeviceUser.ATTRIBUTE) DeviceUser user, @RequestBody StartRequest request) {
        return packing.start(user.id(), request.locationId(), request.lpnId(), Boolean.TRUE.equals(request.takeOver()));
    }

    @GetMapping("/tasks/{id}")
    public PackTaskView task(@PathVariable UUID id) {
        return packing.get(id);
    }

    @PostMapping("/tasks/{id}/cartons")
    public PackTaskView openCarton(@RequestAttribute(DeviceUser.ATTRIBUTE) DeviceUser user, @PathVariable UUID id,
                                   @RequestBody CartonRequest request) {
        return packing.openCarton(user.id(), id, request.orderId(), request.containerTypeId(), request.lpnId());
    }

    @PostMapping("/tasks/{id}/pack")
    public PackTaskView pack(@RequestAttribute(DeviceUser.ATTRIBUTE) DeviceUser user, @PathVariable UUID id,
                             @RequestBody PackingService.Pack request) {
        return packing.pack(user.id(), id, request);
    }

    @PostMapping("/tasks/{id}/cartons/{cartonId}/empty")
    public PackTaskView emptyCarton(@RequestAttribute(DeviceUser.ATTRIBUTE) DeviceUser user, @PathVariable UUID id,
                                    @PathVariable UUID cartonId) {
        return packing.emptyCarton(user.id(), id, cartonId);
    }

    @PostMapping("/tasks/{id}/cartons/{cartonId}/close")
    public PackTaskView closeCarton(@RequestAttribute(DeviceUser.ATTRIBUTE) DeviceUser user, @PathVariable UUID id,
                                    @PathVariable UUID cartonId, @RequestBody CloseRequest request) {
        return packing.closeCarton(user.id(), id, cartonId, request.weight(), request.weightUom());
    }

    @PostMapping("/tasks/{id}/pack-as-is")
    public PackTaskView packAsIs(@RequestAttribute(DeviceUser.ATTRIBUTE) DeviceUser user, @PathVariable UUID id,
                                 @RequestBody CloseRequest request) {
        return packing.packAsIs(user.id(), id, request.weight(), request.weightUom());
    }

    @PostMapping("/tasks/{id}/missing")
    public PackTaskView reportMissing(@RequestAttribute(DeviceUser.ATTRIBUTE) DeviceUser user, @PathVariable UUID id,
                                      @RequestBody MissingRequest request) {
        return packing.reportMissing(user.id(), id, request.detailId(), request.remaining());
    }
}
