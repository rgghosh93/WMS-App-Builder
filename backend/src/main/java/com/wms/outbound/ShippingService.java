package com.wms.outbound;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wms.common.ActiveStatus;
import com.wms.common.Codes;
import com.wms.common.api.ApiException;
import com.wms.inventory.InventoryRecord;
import com.wms.inventory.InventoryRepository;
import com.wms.inventory.StockLedger;
import com.wms.inventory.StockLedger.Note;
import com.wms.inventory.StockLedger.Place;
import com.wms.inventory.StockTransaction;
import com.wms.masterdata.carrier.Carrier;
import com.wms.masterdata.carrier.CarrierRepository;
import com.wms.masterdata.carrier.CarrierService;
import com.wms.masterdata.carrier.CarrierServiceRepository;
import com.wms.masterdata.carrier.CarrierUsage;
import com.wms.masterdata.container.Container;
import com.wms.masterdata.container.ContainerStatus;
import com.wms.masterdata.location.Location;
import com.wms.masterdata.location.LocationRepository;
import com.wms.masterdata.location.LocationType;
import com.wms.outbound.OrderEvent.Type;
import com.wms.outbound.OutboundViews.LocationRef;
import com.wms.outbound.ShippingViews.DoorState;
import com.wms.outbound.ShippingViews.DoorView;
import com.wms.outbound.ShippingViews.ManifestView;
import com.wms.outbound.ShippingViews.ShipmentView;
import com.wms.users.AppUser;
import com.wms.users.AppUserRepository;

/**
 * Shipping: closed cartons become shipments, shipments are loaded onto a manifest at a dock
 * door, and closing the manifest ships them, taking their stock out of inventory.
 */
@Service
@Transactional
public class ShippingService implements CarrierUsage {

    private static final int MAX_TRACKING = 60;
    private static final int MAX_TRAILER = 40;

    private final ShipmentRepository shipments;
    private final ManifestRepository manifests;
    private final PackCartonRepository cartons;
    private final OrderRepository orders;
    private final AllocationRepository allocations;
    private final InventoryRepository inventory;
    private final CarrierRepository carriers;
    private final CarrierServiceRepository services;
    private final LocationRepository locations;
    private final AppUserRepository users;
    private final OrderEventRepository events;

    private final StockLedger ledger;

    ShippingService(ShipmentRepository shipments, ManifestRepository manifests, PackCartonRepository cartons,
                    OrderRepository orders, AllocationRepository allocations, InventoryRepository inventory,
                    CarrierRepository carriers, CarrierServiceRepository services, LocationRepository locations,
                    AppUserRepository users, OrderEventRepository events, StockLedger ledger) {
        this.ledger = ledger;
        this.shipments = shipments;
        this.manifests = manifests;
        this.cartons = cartons;
        this.orders = orders;
        this.allocations = allocations;
        this.inventory = inventory;
        this.carriers = carriers;
        this.services = services;
        this.locations = locations;
        this.users = users;
        this.events = events;
    }

    // ---------- Shipments ----------

    /** Puts a carton just closed on its order's open shipment, starting one if there is none. */
    void addCarton(PackCarton carton) {
        openShipment(carton.getOrder(), null).add(carton);
    }

    private Shipment openShipment(OutboundOrder order, CarrierService service) {
        return shipments.findByOrder_IdOrderByShipmentNumber(order.getId()).stream()
                .filter(s -> s.getStatus() == ShipmentStatus.OPEN).findFirst()
                .orElseGet(() -> {
                    Shipment shipment = new Shipment(UUID.randomUUID(), "SH%06d".formatted(shipments.nextNumber()), order);
                    shipment.setCarrierService(service);
                    return shipments.save(shipment);
                });
    }

    @Transactional(readOnly = true)
    public Page<ShipmentView> search(ShipmentSearch search, Pageable pageable) {
        return shipments.findAll(search.toSpecification(), pageable).map(ShipmentView::of);
    }

    @Transactional(readOnly = true)
    public ShipmentView get(UUID id) {
        return ShipmentView.of(load(id));
    }

    @Transactional(readOnly = true)
    public List<ShipmentView> forOrder(UUID orderId) {
        return shipments.findByOrder_IdOrderByShipmentNumber(orderId).stream().map(ShipmentView::of).toList();
    }

    /** Chooses how a shipment goes. Only before any of it is loaded. */
    public ShipmentView setService(UUID id, UUID serviceId, Long version) {
        Shipment shipment = load(id);
        requireOpen(shipment);
        if (version == null || version != shipment.getVersion()) {
            throw ApiException.conflict("This shipment was changed by someone else. Reload it and try again.", Map.of());
        }
        CarrierService service = null;
        if (serviceId != null) {
            service = services.findById(serviceId)
                    .orElseThrow(() -> invalid("carrierServiceId", "Choose a carrier service"));
            boolean changing = shipment.getCarrierService() == null || !shipment.getCarrierService().getId().equals(serviceId);
            if (changing && (service.getStatus() != ActiveStatus.ACTIVE || service.getCarrier().getStatus() != ActiveStatus.ACTIVE)) {
                throw invalid("carrierServiceId", service.getCarrier().getName() + " " + service.getName() + " is inactive");
            }
        }
        shipment.setCarrierService(service);
        shipments.flush();
        return ShipmentView.of(shipment);
    }

    /** Sets where an order goes. Not once any of it is loaded or shipped. */
    public ShipTo setShipTo(UUID orderId, ShipTo shipTo) {
        OutboundOrder order = orders.findById(orderId).orElseThrow(() -> ApiException.notFound("Order not found"));
        if (order.getStatus() == OrderStatus.CANCELLED) {
            throw ApiException.conflict("Order " + order.getOrderNumber() + " is cancelled.", Map.of());
        }
        shipments.findByOrder_IdOrderByShipmentNumber(orderId).forEach(ShippingService::requireOpen);
        order.setShipTo(shipTo.tidied());
        orders.flush();
        return order.getShipTo();
    }

    /** Records the carrier's tracking number for a carton; blank clears it. */
    public ShipmentView setTracking(UUID shipmentId, UUID cartonId, String tracking) {
        Shipment shipment = load(shipmentId);
        PackCarton carton = shipment.getCartons().stream().filter(c -> c.getId().equals(cartonId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Carton not found on this shipment"));
        if (shipment.getStatus() == ShipmentStatus.SHIPPED) {
            throw ApiException.conflict(shipment.getShipmentNumber() + " has shipped.", Map.of());
        }
        if (carton.isLoaded()) {
            throw ApiException.conflict(carton.getContainer().getLpnId() + " is already loaded.", Map.of());
        }
        String number = Codes.normalize(tracking);
        if (number != null && number.length() > MAX_TRACKING) {
            throw invalid("trackingNumber", "At most " + MAX_TRACKING + " characters");
        }
        if (number != null) {
            cartons.findByTrackingNumber(number).filter(other -> !other.getId().equals(cartonId)).ifPresent(other -> {
                throw ApiException.conflict("That tracking number is already used",
                        Map.of("trackingNumber", "already on carton " + other.getContainer().getLpnId()));
            });
        }
        carton.setTrackingNumber(number);
        shipment.touch();
        shipments.flush();
        return ShipmentView.of(shipment);
    }

    // ---------- Manifests ----------

    @Transactional(readOnly = true)
    public Page<ManifestView> searchManifests(ManifestSearch search, Pageable pageable) {
        return manifests.findAll(search.toSpecification(), pageable).map(m -> ManifestView.of(m, m.getShipments()));
    }

    @Transactional(readOnly = true)
    public ManifestView getManifest(UUID id) {
        Manifest manifest = manifests.findById(id).orElseThrow(() -> ApiException.notFound("Manifest not found"));
        return ManifestView.of(manifest, manifest.getShipments());
    }

    @Transactional(readOnly = true)
    public List<DoorView> doors() {
        return locations.findByTypeOrderByLocationId(LocationType.DOCK).stream().map(l -> {
            Manifest open = manifests.findByOpenDoorId(l.getId()).orElse(null);
            return new DoorView(l.getId(), l.getLocationId(), open == null ? null : open.getManifestNumber(),
                    open == null ? null : open.getCarrier().getName());
        }).toList();
    }

    @Transactional(readOnly = true)
    public DoorState door(String locationId) {
        return state(loadDoor(locationId));
    }

    /** Starts loading a carrier at a door. */
    public DoorState openManifest(UUID userId, String locationId, UUID carrierId) {
        Location door = loadDoor(locationId);
        manifests.findByOpenDoorId(door.getId()).ifPresent(m -> {
            throw ApiException.conflict(m.getManifestNumber() + " is already open at " + door.getLocationId() + ".", Map.of());
        });
        Carrier carrier = carrierId == null ? null : carriers.findById(carrierId).orElse(null);
        if (carrier == null || carrier.getStatus() != ActiveStatus.ACTIVE) {
            throw invalid("carrierId", "Choose a carrier");
        }
        manifests.saveAndFlush(new Manifest(UUID.randomUUID(), "MF%06d".formatted(manifests.nextNumber()), carrier, door,
                users.getReferenceById(userId)));
        return state(door);
    }

    /** Loads a scanned carton. */
    public DoorState load(UUID userId, UUID manifestId, String scannedLpn) {
        Manifest manifest = lockOpen(manifestId);
        PackCarton carton = carton(scannedLpn);
        String lpn = carton.getContainer().getLpnId();
        Shipment shipment = carton.getShipment();
        if (carton.getStatus() != CartonStatus.CLOSED || shipment == null) {
            throw invalid("lpnId", lpn + " is not closed yet");
        }
        if (shipment.getStatus() == ShipmentStatus.SHIPPED) {
            throw invalid("lpnId", lpn + " has already shipped");
        }
        if (carton.isLoaded()) {
            boolean here = shipment.getManifest().getId().equals(manifestId);
            throw invalid("lpnId", lpn + " is already loaded" + (here ? "" : " on " + shipment.getManifest().getManifestNumber()));
        }
        if (shipment.getManifest() != null && !shipment.getManifest().getId().equals(manifestId)) {
            throw invalid("lpnId", "The rest of " + shipment.getShipmentNumber() + " is on " + shipment.getManifest().getManifestNumber());
        }
        if (shipment.getOrder().getShipTo() == null) {
            throw invalid("lpnId", shipment.getShipmentNumber() + " has no ship-to address");
        }
        if (shipment.getCarrierService() == null) {
            throw invalid("lpnId", shipment.getShipmentNumber() + " has no carrier service");
        }
        Carrier carrier = shipment.getCarrierService().getCarrier();
        if (!carrier.getId().equals(manifest.getCarrier().getId())) {
            throw invalid("lpnId", lpn + " goes with " + carrier.getName() + ", not " + manifest.getCarrier().getName());
        }
        if (carrier.isTrackingRequired() && carton.getTrackingNumber() == null) {
            throw invalid("lpnId", lpn + " has no tracking number");
        }
        if (isHeld(carton)) {
            throw invalid("lpnId", lpn + " holds stock that is on hold or has expired");
        }
        carton.load(users.getReferenceById(userId));
        carton.getContainer().setCurrentLocation(manifest.getDoor());
        shipment.loadedOn(manifest);
        shipments.flush();
        return state(manifest.getDoor());
    }

    /** Takes a carton back off; it goes back to where it was packed. */
    public DoorState unload(UUID manifestId, String scannedLpn) {
        Manifest manifest = lockOpen(manifestId);
        PackCarton carton = carton(scannedLpn);
        Shipment shipment = carton.getShipment();
        if (!carton.isLoaded() || shipment.getManifest() == null || !shipment.getManifest().getId().equals(manifestId)) {
            throw invalid("lpnId", carton.getContainer().getLpnId() + " is not loaded here");
        }
        carton.unload();
        carton.getContainer().setCurrentLocation(carton.getTask().getStation());
        shipment.unloaded();
        shipments.flush();
        return state(manifest.getDoor());
    }

    /** Gives up on a manifest with nothing loaded. */
    public DoorState discard(UUID manifestId) {
        Manifest manifest = lockOpen(manifestId);
        if (!shipments.findByManifest_IdOrderByShipmentNumber(manifestId).isEmpty()) {
            throw ApiException.conflict("Unload everything from " + manifest.getManifestNumber() + " first.", Map.of());
        }
        Location door = manifest.getDoor();
        manifests.delete(manifest);
        manifests.flush();
        return state(door);
    }

    /**
     * Ship confirm: closes the manifest and ships what is loaded.
     *
     * @param leaveBehind ship incomplete shipments without the cartons that are not loaded; those
     *                    move to a shipment of their own. Without it, incomplete shipments stop the close.
     */
    public ManifestView close(UUID userId, UUID manifestId, String trailer, String seal, boolean leaveBehind) {
        Manifest manifest = lockOpen(manifestId);
        List<Shipment> onBoard = shipments.findByManifest_IdOrderByShipmentNumber(manifestId);
        if (onBoard.isEmpty()) {
            throw ApiException.conflict("Nothing is loaded on " + manifest.getManifestNumber() + ".", Map.of());
        }
        String trailerNumber = checkedLength("trailerNumber", Codes.normalize(trailer));
        String sealNumber = checkedLength("sealNumber", Codes.normalize(seal));
        List<PackCarton> notLoaded = onBoard.stream().flatMap(s -> s.getCartons().stream()).filter(c -> !c.isLoaded()).toList();
        if (!notLoaded.isEmpty() && !leaveBehind) {
            String list = String.join(", ", notLoaded.stream().map(c -> c.getContainer().getLpnId()).toList());
            throw ApiException.conflict("Not loaded yet: " + list + ".", Map.of("cartons", list));
        }
        List<String> held = onBoard.stream().flatMap(s -> s.getCartons().stream())
                .filter(c -> c.isLoaded() && isHeld(c)).map(c -> c.getContainer().getLpnId()).toList();
        if (!held.isEmpty()) {
            throw ApiException.conflict("Unload " + String.join(", ", held) + ": stock inside is on hold or has expired.", Map.of());
        }

        for (PackCarton carton : notLoaded) {
            Shipment from = carton.getShipment();
            from.remove(carton);
            openShipment(from.getOrder(), from.getCarrierService()).add(carton);
        }

        AppUser user = users.getReferenceById(userId);
        Instant now = Instant.now();
        manifest.close(trailerNumber, sealNumber, user, now);
        for (Shipment shipment : onBoard) {
            ship(shipment, manifest, now, userId);
        }
        shipments.flush();
        return ManifestView.of(manifest, onBoard);
    }

    /** Takes a shipment's stock out of inventory and closes out its cartons. */
    private void ship(Shipment shipment, Manifest manifest, Instant at, UUID userId) {
        int eaches = 0;
        for (PackCarton carton : shipment.getCartons()) {
            for (PackCartonLine line : carton.getLines()) {
                Allocation allocation = line.getAllocation();
                line.clearAllocation();
                line.getDetail().getLine().shipped(line.getQuantity());
                eaches += line.getQuantity();
                if (allocation != null) {
                    line.getDetail().getPickDetail().forgetPicked(allocation);
                    shipments.flush();
                    allocations.delete(allocation);
                    allocations.flush();
                    InventoryRecord gone = allocation.getInventory();
                    ledger.log(StockTransaction.Type.SHIP, gone, gone.getQuantity(), Place.of(gone), Place.OUTSIDE,
                            Note.by(userId, manifest.getManifestNumber()));
                    inventory.delete(gone);
                }
            }
            // The LPN stays as a record of what went out, but it is no longer in the building.
            Container container = carton.getContainer();
            container.setStatus(ContainerStatus.RETIRED);
            container.setCurrentLocation(null);
        }
        shipment.ship(at);
        OutboundOrder order = shipment.getOrder();
        order.refreshStatus();
        int count = shipment.getCartons().size();
        events.save(new OrderEvent(order, Type.SHIPPED, "Shipped %d EA in %d carton%s with %s %s on %s (%s)".formatted(
                eaches, count, count == 1 ? "" : "s", manifest.getCarrier().getName(), shipment.getCarrierService().getName(),
                manifest.getManifestNumber(), shipment.getShipmentNumber())));
    }

    // ---------- Carriers in use ----------

    @Override
    @Transactional(readOnly = true)
    public Optional<String> describeUse(UUID carrierId) {
        List<String> uses = new ArrayList<>();
        long shipmentCount = shipments.countByCarrierService_Carrier_Id(carrierId);
        if (shipmentCount > 0) {
            uses.add(shipmentCount == 1 ? "1 shipment uses it" : shipmentCount + " shipments use it");
        }
        long manifestCount = manifests.countByCarrier_Id(carrierId);
        if (manifestCount > 0) {
            uses.add(manifestCount == 1 ? "1 manifest uses it" : manifestCount + " manifests use it");
        }
        return uses.isEmpty() ? Optional.empty() : Optional.of(String.join("; ", uses));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean usesService(UUID serviceId) {
        return shipments.countByCarrierService_Id(serviceId) > 0;
    }

    // ---------- Helpers ----------

    private DoorState state(Location door) {
        Manifest manifest = manifests.findByOpenDoorId(door.getId()).orElse(null);
        if (manifest == null) {
            return new DoorState(LocationRef.of(door), null, List.of());
        }
        return new DoorState(LocationRef.of(door),
                ManifestView.of(manifest, shipments.findByManifest_IdOrderByShipmentNumber(manifest.getId())),
                shipments.findWaitingFor(manifest.getCarrier().getId()).stream().map(ShipmentView::of).toList());
    }

    private Shipment load(UUID id) {
        return shipments.findById(id).orElseThrow(() -> ApiException.notFound("Shipment not found"));
    }

    private Manifest lockOpen(UUID id) {
        Manifest manifest = manifests.findLocked(id).orElseThrow(() -> ApiException.notFound("Manifest not found"));
        if (manifest.getStatus() != ManifestStatus.OPEN) {
            throw ApiException.conflict(manifest.getManifestNumber() + " is already closed.", Map.of());
        }
        return manifest;
    }

    private PackCarton carton(String scannedLpn) {
        String lpn = Codes.normalize(scannedLpn);
        if (lpn == null) {
            throw invalid("lpnId", "Scan a carton");
        }
        return cartons.findByLpn(lpn).orElseThrow(() -> invalid("lpnId", lpn + " is not a shipping carton"));
    }

    private Location loadDoor(String locationId) {
        String code = Codes.normalize(locationId);
        Location door = code == null ? null : locations.findByLocationId(code).orElse(null);
        if (door == null || door.getType() != LocationType.DOCK) {
            throw invalid("locationId", code == null ? "Scan a dock door" : code + " is not a dock door");
        }
        return door;
    }

    /** Stock inside was put on hold, found damaged or expired after it was packed. */
    private static boolean isHeld(PackCarton carton) {
        return carton.getLines().stream().map(PackCartonLine::getAllocation)
                .anyMatch(a -> a != null && !a.getInventory().isUsable());
    }

    private static void requireOpen(Shipment shipment) {
        if (shipment.getStatus() != ShipmentStatus.OPEN) {
            throw ApiException.conflict(shipment.getShipmentNumber()
                    + (shipment.getStatus() == ShipmentStatus.SHIPPED ? " has shipped." : " is being loaded."), Map.of());
        }
    }

    private static String checkedLength(String field, String value) {
        if (value != null && value.length() > MAX_TRAILER) {
            throw invalid(field, "At most " + MAX_TRAILER + " characters");
        }
        return value;
    }

    private static ApiException invalid(String field, String message) {
        return ApiException.invalid(message, Map.of(field, message));
    }
}
