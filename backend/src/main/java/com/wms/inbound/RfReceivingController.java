package com.wms.inbound;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.wms.inbound.InboundViews.AsnDetail;
import com.wms.inbound.InboundViews.AsnSummary;
import com.wms.inbound.InboundViews.ItemInfo;
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
    public AsnDetail asn(@PathVariable String asnNumber, @RequestParam(required = false) String owner) {
        return asns.getByNumber(asnNumber, owner);
    }

    /** @param requestId made by the device; it becomes the ID of the receipt, so sending it again starts nothing more */
    public record BlindRequest(UUID requestId, String owner, String vendorName, String reference) {
    }

    /** Starts receiving what nobody announced. From there on it is received like an ASN. */
    @PostMapping("/blind")
    public AsnDetail startBlind(@RequestAttribute(DeviceUser.ATTRIBUTE) DeviceUser user, @RequestBody BlindRequest request) {
        return asns.startBlind(request.requestId(), request.owner(), request.vendorName(), request.reference(), user.displayName());
    }

    /** Gives up on a blind receipt with nothing received. */
    @PostMapping("/blind/{id}/discard")
    public AsnDetail discardBlind(@PathVariable UUID id) {
        return asns.discardBlind(id);
    }

    /** An item to receive blind. */
    @GetMapping("/blind/{id}/items/{code}")
    public ItemInfo blindItem(@PathVariable UUID id, @PathVariable String code) {
        return receiving.blindItem(id, code);
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
