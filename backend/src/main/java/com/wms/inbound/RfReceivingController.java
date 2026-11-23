package com.wms.inbound;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.wms.inbound.InboundViews.AsnDetail;
import com.wms.inbound.InboundViews.AsnSummary;
import com.wms.inbound.InboundViews.LocationRef;
import com.wms.inbound.InboundViews.LpnCheck;
import com.wms.users.DeviceUser;
import com.wms.users.Permission;
import com.wms.users.Requires;

/** Receiving ASNs on a mobile device. */
@RestController
@Requires(Permission.DEVICE_RECEIVE)
@RequestMapping("/api/rf/receiving")
public class RfReceivingController {

    private final ReceivingService receiving;
    private final AsnService asns;

    public RfReceivingController(ReceivingService receiving, AsnService asns) {
        this.receiving = receiving;
        this.asns = asns;
    }

    /** @param lpnId blank to have a new LPN number made */
    public record LpnScan(String locationId, String lpnId) {
    }

    public record CloseRequest(Boolean acceptShort) {
    }

    @GetMapping("/locations")
    public List<LocationRef> locations() {
        return receiving.locations();
    }

    @GetMapping("/locations/{locationId}")
    public LocationRef location(@PathVariable String locationId) {
        return receiving.location(locationId);
    }

    @GetMapping("/asns")
    public List<AsnSummary> open() {
        return asns.open();
    }

    @GetMapping("/asns/{asnNumber}")
    public AsnDetail asn(@PathVariable String asnNumber) {
        return asns.getByNumber(asnNumber);
    }

    @PostMapping("/asns/{id}/lpn")
    public LpnCheck checkLpn(@PathVariable UUID id, @RequestBody LpnScan scan) {
        return receiving.checkLpn(id, scan.locationId(), scan.lpnId());
    }

    @PostMapping("/asns/{id}/receive")
    public AsnDetail receive(@RequestAttribute(DeviceUser.ATTRIBUTE) DeviceUser user, @PathVariable UUID id,
                             @RequestBody ReceivingService.Receive request) {
        return receiving.receive(user.id(), id, request);
    }

    @PostMapping("/asns/{id}/receive-lpn")
    public AsnDetail receiveLpn(@RequestAttribute(DeviceUser.ATTRIBUTE) DeviceUser user, @PathVariable UUID id,
                                @RequestBody ReceivingService.ReceiveLpn request) {
        return receiving.receiveLpn(user.id(), id, request);
    }

    @PostMapping("/receipts/{id}/reverse")
    public AsnDetail reverse(@RequestAttribute(DeviceUser.ATTRIBUTE) DeviceUser user, @PathVariable UUID id) {
        return receiving.reverse(user.id(), id);
    }

    @PostMapping("/asns/{id}/close")
    public AsnDetail close(@RequestAttribute(DeviceUser.ATTRIBUTE) DeviceUser user, @PathVariable UUID id,
                           @RequestBody CloseRequest request) {
        return receiving.close(user.id(), id, Boolean.TRUE.equals(request.acceptShort()));
    }
}
