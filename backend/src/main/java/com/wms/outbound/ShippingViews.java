package com.wms.outbound;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.wms.masterdata.carrier.Carrier;
import com.wms.masterdata.carrier.CarrierMode;
import com.wms.masterdata.carrier.CarrierService;
import com.wms.outbound.OutboundViews.LocationRef;
import com.wms.outbound.OutboundViews.OrderRef;
import com.wms.outbound.PackingViews.CartonView;

/** Read models for shipping. */
public final class ShippingViews {

    private ShippingViews() {
    }

    public record CarrierRef(UUID id, String carrierCode, String name, CarrierMode mode, boolean trackingRequired) {
        static CarrierRef of(Carrier c) {
            return new CarrierRef(c.getId(), c.getCarrierCode(), c.getName(), c.getMode(), c.isTrackingRequired());
        }
    }

    public record ServiceRef(UUID id, String serviceCode, String name) {
        static ServiceRef of(CarrierService s) {
            return s == null ? null : new ServiceRef(s.getId(), s.getServiceCode(), s.getName());
        }
    }

    public record ManifestRef(UUID id, String manifestNumber) {
        static ManifestRef of(Manifest m) {
            return m == null ? null : new ManifestRef(m.getId(), m.getManifestNumber());
        }
    }

    /**
     * A shipment with its cartons.
     *
     * @param missing           what still has to be filled in before it can be loaded; empty when ready
     * @param unpackedQuantity  eaches of the order that are not packed, so will not go with this shipment
     */
    public record ShipmentView(UUID id, String shipmentNumber, ShipmentStatus status, OrderRef order, ShipTo shipTo,
                               CarrierRef carrier, ServiceRef service, ManifestRef manifest, int loadedCount,
                               int totalQuantity, int unpackedQuantity, List<String> missing, List<CartonView> cartons,
                               Instant createdAt, Instant shippedAt, long version) {
        static ShipmentView of(Shipment s) {
            boolean shipped = s.getStatus() == ShipmentStatus.SHIPPED;
            int unpacked = shipped ? 0 : s.getOrder().getLines().stream()
                    .filter(l -> l.getStatus() != LineStatus.CANCELLED)
                    .mapToInt(l -> l.neededQuantity() - l.getPackedQuantity()).sum();
            return new ShipmentView(s.getId(), s.getShipmentNumber(), s.getStatus(), OrderRef.of(s.getOrder()),
                    s.getOrder().getShipTo(),
                    s.getCarrierService() == null ? null : CarrierRef.of(s.getCarrierService().getCarrier()),
                    ServiceRef.of(s.getCarrierService()), ManifestRef.of(s.getManifest()),
                    (int) s.getCartons().stream().filter(PackCarton::isLoaded).count(),
                    s.getCartons().stream().mapToInt(PackCarton::totalQuantity).sum(), unpacked,
                    shipped ? List.of() : s.missing(), s.getCartons().stream().map(CartonView::of).toList(),
                    s.getCreatedAt(), s.getShippedAt(), s.getVersion());
        }
    }

    public record ManifestView(UUID id, String manifestNumber, ManifestStatus status, CarrierRef carrier, LocationRef door,
                               String trailerNumber, String sealNumber, int cartonCount, int totalQuantity,
                               List<ShipmentView> shipments, String createdBy, Instant createdAt, String closedBy,
                               Instant closedAt) {
        static ManifestView of(Manifest m, List<Shipment> shipments) {
            List<PackCarton> loaded = shipments.stream().flatMap(s -> s.getCartons().stream()).filter(PackCarton::isLoaded).toList();
            return new ManifestView(m.getId(), m.getManifestNumber(), m.getStatus(), CarrierRef.of(m.getCarrier()),
                    LocationRef.of(m.getDoor()), m.getTrailerNumber(), m.getSealNumber(), loaded.size(),
                    loaded.stream().mapToInt(PackCarton::totalQuantity).sum(),
                    shipments.stream().map(ShipmentView::of).toList(), m.getCreatedBy().getDisplayName(), m.getCreatedAt(),
                    m.getClosedBy() == null ? null : m.getClosedBy().getDisplayName(), m.getClosedAt());
        }
    }

    /** @param manifestNumber the manifest being loaded there, if any */
    public record DoorView(UUID id, String locationId, String manifestNumber, String carrierName) {
    }

    /**
     * What is happening at a dock door.
     *
     * @param manifest the open manifest, or null if loading hasn't started
     * @param waiting  shipments for the manifest's carrier that are not loaded yet
     */
    public record DoorState(LocationRef door, ManifestView manifest, List<ShipmentView> waiting) {
    }
}
