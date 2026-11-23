package com.wms.outbound;

import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.wms.common.api.PageResponse;
import com.wms.outbound.ShippingViews.ManifestView;
import com.wms.outbound.ShippingViews.ShipmentView;
import com.wms.users.Permission;
import com.wms.users.Requires;

import jakarta.validation.Valid;

/** Preparing shipments and looking at manifests from a desk. */
@RestController
public class ShippingController {

    private final ShippingService shipping;

    public ShippingController(ShippingService shipping) {
        this.shipping = shipping;
    }

    public record ServiceRequest(UUID carrierServiceId, Long version) {
    }

    public record TrackingRequest(String trackingNumber) {
    }

    @GetMapping("/api/shipments")
    public PageResponse<ShipmentView> shipments(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) ShipmentStatus status,
            @RequestParam(required = false) UUID carrierId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return PageResponse.of(shipping.search(new ShipmentSearch(q, status, carrierId), newest("shipmentNumber", page, size)), s -> s);
    }

    @GetMapping("/api/shipments/{id}")
    public ShipmentView shipment(@PathVariable UUID id) {
        return shipping.get(id);
    }

    @PutMapping("/api/shipments/{id}/service")
    @Requires(Permission.SHIPPING)
    public ShipmentView setService(@PathVariable UUID id, @RequestBody ServiceRequest request) {
        return shipping.setService(id, request.carrierServiceId(), request.version());
    }

    @PutMapping("/api/shipments/{id}/cartons/{cartonId}/tracking")
    @Requires(Permission.SHIPPING)
    public ShipmentView setTracking(@PathVariable UUID id, @PathVariable UUID cartonId, @RequestBody TrackingRequest request) {
        return shipping.setTracking(id, cartonId, request.trackingNumber());
    }

    @PutMapping("/api/orders/{id}/ship-to")
    @Requires(Permission.SHIPPING)
    public ShipTo setShipTo(@PathVariable UUID id, @Valid @RequestBody ShipTo shipTo) {
        return shipping.setShipTo(id, shipTo);
    }

    @GetMapping("/api/manifests")
    public PageResponse<ManifestView> manifests(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) ManifestStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return PageResponse.of(shipping.searchManifests(new ManifestSearch(q, status), newest("manifestNumber", page, size)), m -> m);
    }

    @GetMapping("/api/manifests/{id}")
    public ManifestView manifest(@PathVariable UUID id) {
        return shipping.getManifest(id);
    }

    private static PageRequest newest(String field, int page, int size) {
        return PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, OutboundController.MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, field));
    }
}
