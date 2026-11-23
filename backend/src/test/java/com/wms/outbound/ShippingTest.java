package com.wms.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.wms.common.ActiveStatus;
import com.wms.common.measure.DimensionUom;
import com.wms.common.measure.WeightUom;
import com.wms.inventory.InventoryRecord;
import com.wms.inventory.InventoryRepository;
import com.wms.inventory.InventoryStatus;
import com.wms.masterdata.carrier.Carrier;
import com.wms.masterdata.carrier.CarrierMode;
import com.wms.masterdata.carrier.CarrierRepository;
import com.wms.masterdata.carrier.CarrierService;
import com.wms.masterdata.container.Container;
import com.wms.masterdata.container.ContainerCategory;
import com.wms.masterdata.container.ContainerRepository;
import com.wms.masterdata.container.ContainerStatus;
import com.wms.masterdata.container.ContainerType;
import com.wms.masterdata.container.ContainerTypeRepository;
import com.wms.masterdata.item.Item;
import com.wms.masterdata.item.ItemRepository;
import com.wms.masterdata.location.Location;
import com.wms.masterdata.location.LocationRepository;
import com.wms.masterdata.location.LocationType;
import com.wms.outbound.PackingViews.PackTaskView;
import com.wms.outbound.PickingViews.PickDirective;
import com.wms.outbound.PickingViews.PickingState;
import com.wms.outbound.PickingViews.Step;
import com.wms.users.TestUsers;

import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ShippingTest {

    private static final String PIN = "1357";
    private static final String STATION = "DC1-PACK-01";
    private static final String DOOR = "DC1-DOCK-01";
    private static final ShipTo ADDRESS = new ShipTo("Acme Retail", "1 Main St", null, "Springfield", "IL", "62701", "US");

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private EntityManager em;
    @Autowired private ItemRepository items;
    @Autowired private LocationRepository locations;
    @Autowired private InventoryRepository inventory;
    @Autowired private ContainerRepository containers;
    @Autowired private ContainerTypeRepository types;
    @Autowired private CarrierRepository carriers;
    @Autowired private TestUsers people;
    @Autowired private OrderService orderService;
    @Autowired private PickingService picking;
    @Autowired private PackingService packing;

    private Item bars;
    private ContainerType cartonType;
    private UUID annId;
    private Cookie ann;
    private Carrier parcel;
    private Carrier freight;
    private int nextLpn;

    /** 100 bars; a parcel carrier that needs tracking numbers and a freight carrier that doesn't; two dock doors. */
    @BeforeEach
    void fixtures() throws Exception {
        Location shelf = location("DC1-BK-01-01-A-01", LocationType.STORAGE, "BK", "01");
        location(STATION, LocationType.PACKING, "PACK", "01");
        location(DOOR, LocationType.DOCK, "DOCK", "01");
        location("DC1-DOCK-02", LocationType.DOCK, "DOCK", "02");
        bars = new Item(UUID.randomUUID());
        bars.setItemId("SKU-BAR");
        bars.setDescription("Bar");
        bars.setStatus(ActiveStatus.ACTIVE);
        bars = items.save(bars);
        InventoryRecord stock = new InventoryRecord(UUID.randomUUID(), bars, 100);
        stock.placeIn(shelf);
        inventory.save(stock);

        cartonType = new ContainerType(UUID.randomUUID());
        cartonType.setContainerTypeId("CTN");
        cartonType.setDescription("Carton");
        cartonType.setCategory(ContainerCategory.CARTON);
        cartonType.setStatus(ActiveStatus.ACTIVE);
        cartonType.setReusable(false);
        cartonType.setWidth(BigDecimal.ONE);
        cartonType.setDepth(BigDecimal.ONE);
        cartonType.setHeight(BigDecimal.ONE);
        cartonType.setDimensionUom(DimensionUom.IN);
        cartonType = types.save(cartonType);

        parcel = carrier("PARCEL", "Parcel Co", true, "GROUND");
        freight = carrier("FREIGHT", "Freight Co", false, "LTL");
        annId = people.operator("ANN", "Ann Lee", PIN).getId();
        sync();
        ann = mvc.perform(json(post("/api/rf/session"), Map.of("userCode", "ann", "pin", PIN)))
                .andReturn().getResponse().getCookie("wms_device");
    }

    // ---------- Shipments ----------

    @Test
    void closingCartonsPutsThemOnTheOrdersShipment() throws Exception {
        String orderId = packed("SO-1", 5);
        JsonNode shipment = only("SO-1");
        assertThat(shipment.get("shipmentNumber").asString()).matches("SH\\d{6}");
        call(get("/api/shipments/" + shipment.get("id").asString()))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.order.orderNumber").value("SO-1"))
                .andExpect(jsonPath("$.cartons", hasSize(1)))
                .andExpect(jsonPath("$.totalQuantity").value(5))
                .andExpect(jsonPath("$.unpackedQuantity").value(0))
                .andExpect(jsonPath("$.missing", hasSize(2)));
        call(get("/api/orders/" + orderId)).andExpect(jsonPath("$.shipments", hasSize(1)));
        for (String q : List.of("so-1", "acme", "olpn-1", shipment.get("shipmentNumber").asString())) {
            call(get("/api/shipments").param("q", q)).andExpect(jsonPath("$.totalElements").value(1));
        }
        call(get("/api/shipments").param("q", "nope")).andExpect(jsonPath("$.totalElements").value(0));
        call(get("/api/shipments").param("status", "SHIPPED")).andExpect(jsonPath("$.totalElements").value(0));
        call(get("/api/shipments/" + UUID.randomUUID())).andExpect(status().isNotFound());
    }

    @Test
    void shipmentsNeedAnAddressAServiceAndTracking() throws Exception {
        String orderId = packed("SO-1", 5);
        JsonNode s = only("SO-1");
        // A bad address is refused field by field.
        call(json(put("/api/orders/" + orderId + "/ship-to"), Map.of("name", " ", "country", "USA")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").exists())
                .andExpect(jsonPath("$.errors.line1").exists())
                .andExpect(jsonPath("$.errors.country").exists());
        address(orderId).andExpect(jsonPath("$.country").value("US"));
        service(s, parcel).andExpect(status().isOk())
                .andExpect(jsonPath("$.carrier.carrierCode").value("PARCEL"))
                .andExpect(jsonPath("$.missing[0]", containsString("tracking numbers (1 carton)")));
        call(get("/api/shipments").param("carrierId", parcel.getId().toString())).andExpect(jsonPath("$.totalElements").value(1));
        service(s, freight).andExpect(status().isConflict());
        s = only("SO-1");
        tracking(s, 0, " trk-1 ").andExpect(jsonPath("$.cartons[0].trackingNumber").value("TRK-1"))
                .andExpect(jsonPath("$.missing", hasSize(0)));
        call(get("/api/shipments").param("q", "trk-1")).andExpect(jsonPath("$.totalElements").value(1));

        packed("SO-2", 1);
        tracking(only("SO-2"), 0, "TRK-1").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors.trackingNumber", containsString("OLPN-1")));
        tracking(s, 0, "").andExpect(jsonPath("$.missing", hasSize(1)));
    }

    @Test
    void inactiveServicesCantBeChosen() throws Exception {
        packed("SO-1", 1);
        carriers.findById(parcel.getId()).orElseThrow().getServices().getFirst().setStatus(ActiveStatus.INACTIVE);
        service(only("SO-1"), parcel).andExpect(jsonPath("$.errors.carrierServiceId", containsString("inactive")));
    }

    @Test
    void theAddressCanComeWithTheOrder() throws Exception {
        sync();
        UUID id = orderService.create(new OrderRequest("SO-1", "Acme", null, 5,
                List.of(new OrderRequest.Line("SKU-BAR", 1, null)), ADDRESS)).id();
        call(get("/api/orders/" + id)).andExpect(jsonPath("$.shipTo.city").value("Springfield"));
        call(json(post("/api/orders"), Map.of("customerName", "Acme", "lines", List.of(Map.of("itemId", "SKU-BAR", "quantity", 1)),
                "shipTo", Map.of("name", "Acme")))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['shipTo.line1']").exists());
    }

    // ---------- Loading and ship confirm ----------

    @Test
    void loadsAndShipsAnOrder() throws Exception {
        String orderId = ready("SO-1", 5, freight);
        call(get("/api/rf/loading/doors")).andExpect(jsonPath("$", hasSize(2)));
        JsonNode manifest = open(freight);
        call(get("/api/rf/loading/doors/" + DOOR))
                .andExpect(jsonPath("$.manifest.manifestNumber", matchesPattern("MF\\d{6}")))
                .andExpect(jsonPath("$.waiting", hasSize(1)));
        close(manifest, false).andExpect(status().isConflict()).andExpect(jsonPath("$.detail", containsString("Nothing is loaded")));
        load(manifest, "olpn-1")
                .andExpect(jsonPath("$.manifest.cartonCount").value(1))
                .andExpect(jsonPath("$.waiting", hasSize(0)))
                .andExpect(jsonPath("$.manifest.shipments[0].status").value("LOADING"));
        assertThat(container("OLPN-1").getCurrentLocation().getLocationId()).isEqualTo(DOOR);
        load(manifest, "OLPN-1").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.lpnId").value("OLPN-1 is already loaded"));
        // Being loaded, nothing about it can change.
        service(only("SO-1"), parcel).andExpect(status().isConflict());
        address(orderId).andExpect(status().isConflict());
        tracking(only("SO-1"), 0, "X").andExpect(status().isConflict());

        close(manifest, false)
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.trailerNumber").value("TR-9"))
                .andExpect(jsonPath("$.closedBy").value("Ann Lee"))
                .andExpect(jsonPath("$.shipments[0].status").value("SHIPPED"));
        sync();
        assertThat(inventory.sumQuantityByItem(bars.getId())).isEqualTo(95);
        assertThat(container("OLPN-1").getStatus()).isEqualTo(ContainerStatus.RETIRED);
        assertThat(container("OLPN-1").getCurrentLocation()).isNull();
        call(get("/api/orders/" + orderId))
                .andExpect(jsonPath("$.status").value("SHIPPED"))
                .andExpect(jsonPath("$.lines[0].status").value("SHIPPED"))
                .andExpect(jsonPath("$.lines[0].shippedQuantity").value(5))
                .andExpect(jsonPath("$.allocations", hasSize(0)))
                .andExpect(jsonPath("$.packTasks[0].cartons[0].contents[0].quantity").value(5))
                .andExpect(jsonPath("$.events[?(@.type == 'SHIPPED')].message",
                        hasItem(containsString("Shipped 5 EA in 1 carton with Freight Co LTL on MF"))));
        call(get("/api/manifests").param("q", "so-1")).andExpect(jsonPath("$.totalElements").value(1));
        call(get("/api/manifests").param("q", "tr-9")).andExpect(jsonPath("$.totalElements").value(1));
        call(get("/api/manifests").param("status", "OPEN")).andExpect(jsonPath("$.totalElements").value(0));
        call(get("/api/manifests/" + manifest.get("id").asString())).andExpect(jsonPath("$.totalQuantity").value(5));
        // It is over: no more loading, closing, cancelling or allocating; and the door is free.
        load(manifest, "OLPN-1").andExpect(status().isConflict());
        close(manifest, false).andExpect(status().isConflict());
        tracking(only("SO-1"), 0, "X").andExpect(status().isConflict());
        call(post("/api/orders/" + orderId + "/cancel")).andExpect(status().isConflict());
        call(post("/api/orders/" + orderId + "/allocate")).andExpect(status().isConflict());
        JsonNode next = open(freight);
        load(next, "OLPN-1").andExpect(jsonPath("$.errors.lpnId", containsString("already shipped")));
    }

    @Test
    void onlyReadyCartonsForTheCarrierCanBeLoaded() throws Exception {
        JsonNode manifest = open(parcel);
        load(manifest, "NOPE").andExpect(jsonPath("$.errors.lpnId", containsString("not a shipping carton")));
        load(manifest, " ").andExpect(status().isBadRequest());
        String orderId = packed("SO-1", 2);
        load(manifest, "OLPN-1").andExpect(jsonPath("$.errors.lpnId", containsString("no ship-to address")));
        address(orderId);
        load(manifest, "OLPN-1").andExpect(jsonPath("$.errors.lpnId", containsString("no carrier service")));
        service(only("SO-1"), freight);
        load(manifest, "OLPN-1").andExpect(jsonPath("$.errors.lpnId").value("OLPN-1 goes with Freight Co, not Parcel Co"));
        service(only("SO-1"), parcel);
        load(manifest, "OLPN-1").andExpect(jsonPath("$.errors.lpnId", containsString("no tracking number")));
        tracking(only("SO-1"), 0, "TRK-1");
        load(manifest, "OLPN-1").andExpect(status().isOk());
        mvc.perform(get("/api/rf/loading/doors")).andExpect(status().isUnauthorized());
    }

    @Test
    void cartonsStillBeingPackedCantBeLoaded() throws Exception {
        JsonNode manifest = open(freight);
        order("SO-1", 2);
        PackTaskView task = start(pick());
        packing.openCarton(annId, task.id(), task.orders().getFirst().id(), cartonType.getId(), "BOX-1");
        load(manifest, "BOX-1").andExpect(jsonPath("$.errors.lpnId", containsString("not closed yet")));
    }

    @Test
    void stockPutOnHoldAfterPackingCantLeave() throws Exception {
        ready("SO-1", 2, freight);
        JsonNode manifest = open(freight);
        load(manifest, "OLPN-1");
        hold("OLPN-1", InventoryStatus.ON_HOLD);
        close(manifest, false).andExpect(status().isConflict()).andExpect(jsonPath("$.detail", containsString("on hold")));
        unload(manifest, "OLPN-1").andExpect(status().isOk());
        load(manifest, "OLPN-1").andExpect(jsonPath("$.errors.lpnId", containsString("on hold")));
    }

    @Test
    void stockThatExpiresAfterPackingCantLeave() throws Exception {
        ready("SO-1", 2, freight);
        JsonNode manifest = open(freight);
        inventory.findByContainer_IdIn(List.of(container("OLPN-1").getId())).getFirst().setExpiryDate(java.time.LocalDate.now().minusDays(1));
        load(manifest, "OLPN-1").andExpect(jsonPath("$.errors.lpnId", containsString("has expired")));
    }

    @Test
    void whatCannotBeFilledCanBeClosedShort() throws Exception {
        // 100 on hand: 100 of 120 are allocated, picked, packed and shipped.
        String orderId = ready("SO-1", 120, freight);
        JsonNode manifest = open(freight);
        load(manifest, "OLPN-1");
        close(manifest, false).andExpect(status().isOk());
        call(get("/api/orders/" + orderId)).andExpect(jsonPath("$.status").value("PARTIALLY_SHIPPED"));
        call(post("/api/orders/" + orderId + "/close-short"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SHIPPED"))
                .andExpect(jsonPath("$.lines[0].status").value("SHIPPED"))
                .andExpect(jsonPath("$.lines[0].orderedQuantity").value(120))
                .andExpect(jsonPath("$.lines[0].closedQuantity").value(20))
                .andExpect(jsonPath("$.events[?(@.type == 'CLOSED_SHORT')].message", hasItem(containsString("20 EA will not be shipped"))));
        call(post("/api/orders/" + orderId + "/close-short")).andExpect(status().isConflict());
        call(post("/api/orders/" + orderId + "/allocate")).andExpect(status().isConflict());
        call(get("/api/orders")).andExpect(jsonPath("$.content[0].orderedQuantity").value(100));
    }

    @Test
    void closingShortReleasesWhatIsNotPickedAndShipsTheRest() throws Exception {
        // Two lines' worth on one task, plus a second task for stock allocated later.
        sync();
        UUID id = orderService.create(new OrderRequest("SO-1", "Acme", null, 5, List.of(
                new OrderRequest.Line("SKU-BAR", 2, null), new OrderRequest.Line("SKU-BAR", 120, null)), ADDRESS)).id();
        String orderId = id.toString();
        call(post("/api/orders/" + orderId + "/close-short")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("Cancel it instead")));
        sync();
        PickingState state = picking.start(annId, "OLPN-X", cartonType.getId());
        call(post("/api/orders/" + orderId + "/close-short")).andExpect(status().isConflict());
        sync();
        PickDirective p = state.pick();
        picking.confirm(annId, new PickingService.Confirm(p.detailId(), 0, p.locationId(), null, "SKU-BAR", null, 1, false));
        // Being picked: closing now would pull the task from under the picker.
        call(post("/api/orders/" + orderId + "/close-short")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("is being picked")));
        state = picking.state(annId);
        while (state.step() == Step.PICK) {
            p = state.pick();
            sync();
            state = picking.confirm(annId, new PickingService.Confirm(p.detailId(), p.pickedQuantity(), p.locationId(),
                    null, "SKU-BAR", null, p.remaining(), false));
        }
        sync();
        picking.drop(annId, STATION);
        sync();
        // New stock is allocated on a second task, which nobody has started.
        InventoryRecord more = new InventoryRecord(UUID.randomUUID(), items.findByItemId("SKU-BAR").orElseThrow(), 10);
        more.placeIn(locations.findByLocationId("DC1-BK-01-01-A-01").orElseThrow());
        inventory.save(more);
        call(post("/api/orders/" + orderId + "/allocate")).andExpect(jsonPath("$.lines[1].allocatedQuantity").value(108));
        call(post("/api/orders/" + orderId + "/close-short"))
                .andExpect(jsonPath("$.status").value("PICKED"))
                .andExpect(jsonPath("$.lines[1].closedQuantity").value(22))
                .andExpect(jsonPath("$.lines[1].allocatedQuantity").value(98))
                .andExpect(jsonPath("$.tasks[1].status").value("CANCELLED"))
                .andExpect(jsonPath("$.events[?(@.type == 'CLOSED_SHORT')].message", hasItem(containsString("released 10 EA"))));
        // The released stock is free for others, and the rest ships as a complete order.
        String other = order("SO-2", 10);
        call(get("/api/orders/" + other)).andExpect(jsonPath("$.status").value("ALLOCATED"));
        sync();
        PackTaskView task = packing.start(annId, STATION, "OLPN-X", false);
        sync();
        packing.packAsIs(annId, task.id(), null, null);
        service(only("SO-1"), freight).andExpect(jsonPath("$.unpackedQuantity").value(0));
        JsonNode manifest = open(freight);
        load(manifest, "OLPN-X");
        close(manifest, false).andExpect(status().isOk());
        call(get("/api/orders/" + orderId)).andExpect(jsonPath("$.status").value("SHIPPED"));
    }

    @Test
    void cartonsCanBeTakenBackOff() throws Exception {
        ready("SO-1", 2, freight);
        JsonNode manifest = open(freight);
        unload(manifest, "OLPN-1").andExpect(jsonPath("$.errors.lpnId", containsString("not loaded here")));
        load(manifest, "OLPN-1");
        call(post(url(manifest, "discard"))).andExpect(status().isConflict());
        unload(manifest, "OLPN-1").andExpect(jsonPath("$.manifest.cartonCount").value(0)).andExpect(jsonPath("$.waiting", hasSize(1)));
        assertThat(container("OLPN-1").getCurrentLocation().getLocationId()).isEqualTo(STATION);
        assertThat(only("SO-1").get("status").asString()).isEqualTo("OPEN");
        call(post(url(manifest, "discard"))).andExpect(status().isOk());
        call(get("/api/rf/loading/doors/" + DOOR)).andExpect(jsonPath("$.manifest").doesNotExist());
    }

    @Test
    void aPackedOrderCanBeCancelledUntilItIsLoaded() throws Exception {
        String orderId = ready("SO-1", 2, freight);
        JsonNode manifest = open(freight);
        load(manifest, "OLPN-1").andExpect(status().isOk());
        for (String action : List.of("cancel", "undo")) {
            call(post("/api/orders/" + orderId + "/" + action)).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.detail", containsString("OLPN-1 of SO-1 is loaded on " + manifest.get("manifestNumber").asString())));
        }
        unload(manifest, "OLPN-1").andExpect(status().isOk());
        call(post("/api/orders/" + orderId + "/cancel")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.shipments", hasSize(0)))
                .andExpect(jsonPath("$.lines[0].packedQuantity").value(0));
        call(get("/api/shipments").param("q", "SO-1")).andExpect(jsonPath("$.totalElements").value(0));
        load(manifest, "OLPN-1").andExpect(status().isBadRequest());
        // It goes back to storage as it is.
        call(get("/api/rf/moves/from/OLPN-1")).andExpect(jsonPath("$.whole").value(true));
    }

    @Test
    void anOrderInTwoCartonsIsUndoneWithBoth() throws Exception {
        String orderId = twoCartons("SO-1");
        tracking(only("SO-1"), 0, "TRK-1").andExpect(status().isOk());
        call(post("/api/orders/" + orderId + "/undo")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NEW")).andExpect(jsonPath("$.shipments", hasSize(0)))
                .andExpect(jsonPath("$.events[*].message", hasItem(containsString("To move back to storage: 1 EA in BOX-1 at " + STATION + ", 3 EA in BOX-2"))));
    }

    @Test
    void whatHasShippedCantBeCancelled() throws Exception {
        String orderId = ready("SO-1", 2, freight);
        JsonNode manifest = open(freight);
        load(manifest, "OLPN-1");
        close(manifest, false).andExpect(status().isOk());
        for (String action : List.of("cancel", "undo")) {
            call(post("/api/orders/" + orderId + "/" + action)).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.detail", containsString("has shipped")));
        }
    }

    @Test
    void aDoorHasOneOpenManifest() throws Exception {
        open(freight);
        call(json(post("/api/rf/loading/doors/" + DOOR + "/manifest"), Map.of("carrierId", parcel.getId())))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.detail", containsString("already open")));
        call(json(post("/api/rf/loading/doors/DC1-DOCK-02/manifest"), Map.of("carrierId", UUID.randomUUID())))
                .andExpect(status().isBadRequest());
        call(get("/api/rf/loading/doors/" + STATION)).andExpect(jsonPath("$.errors.locationId", containsString("not a dock door")));
    }

    @Test
    void aShipmentStaysOnOneManifest() throws Exception {
        twoCartons("SO-1");
        JsonNode first = open(freight);
        load(first, "BOX-1").andExpect(status().isOk());
        JsonNode second = body(call(json(post("/api/rf/loading/doors/DC1-DOCK-02/manifest"), Map.of("carrierId", freight.getId())))).get("manifest");
        load(second, "BOX-2").andExpect(jsonPath("$.errors.lpnId", containsString("is on MF")));
        load(second, "BOX-1").andExpect(jsonPath("$.errors.lpnId", containsString("already loaded on MF")));
    }

    @Test
    void cartonsLeftBehindGoOnAShipmentOfTheirOwn() throws Exception {
        String orderId = twoCartons("SO-1");
        JsonNode manifest = open(freight);
        load(manifest, "BOX-1");
        close(manifest, false).andExpect(status().isConflict()).andExpect(jsonPath("$.errors.cartons").value("BOX-2"));
        close(manifest, true)
                .andExpect(jsonPath("$.cartonCount").value(1))
                .andExpect(jsonPath("$.shipments[0].cartons", hasSize(1)));
        call(get("/api/orders/" + orderId))
                .andExpect(jsonPath("$.status").value("PARTIALLY_SHIPPED"))
                .andExpect(jsonPath("$.shipments", hasSize(2)))
                .andExpect(jsonPath("$.shipments[1].status").value("OPEN"))
                .andExpect(jsonPath("$.shipments[1].service.serviceCode").value("LTL"))
                .andExpect(jsonPath("$.shipments[1].missing", hasSize(0)))
                .andExpect(jsonPath("$.allocations", hasSize(1)));

        JsonNode later = open(freight);
        load(later, "BOX-2");
        close(later, false).andExpect(status().isOk());
        call(get("/api/orders/" + orderId)).andExpect(jsonPath("$.status").value("SHIPPED"))
                .andExpect(jsonPath("$.shipments[*].status", everyItem(is("SHIPPED"))));
        sync();
        assertThat(inventory.sumQuantityByItem(bars.getId())).isEqualTo(96);
    }

    @Test
    void whatIsShippedBeforeTheRestIsPackedIsAPartialShipment() throws Exception {
        String orderId = order("SO-1", 4);
        address(orderId);
        PackTaskView task = start(pick());
        pack(task, "BOX-1", 1);
        service(only("SO-1"), freight).andExpect(jsonPath("$.unpackedQuantity").value(3));
        JsonNode manifest = open(freight);
        load(manifest, "BOX-1");
        close(manifest, false).andExpect(status().isOk());
        call(get("/api/orders/" + orderId)).andExpect(jsonPath("$.status").value("PARTIALLY_SHIPPED"));
        // The rest is packed onto a new shipment.
        pack(packing.get(task.id()), "BOX-2", 3);
        call(get("/api/orders/" + orderId)).andExpect(jsonPath("$.shipments", hasSize(2)));
        service(body(call(get("/api/shipments").param("q", "box-2"))).get("content").get(0), freight);
        JsonNode later = open(freight);
        load(later, "BOX-2");
        close(later, false).andExpect(status().isOk());
        call(get("/api/orders/" + orderId)).andExpect(jsonPath("$.status").value("SHIPPED"));
    }

    // ---------- Carriers ----------

    @Test
    void managesCarriersAndTheirServices() throws Exception {
        JsonNode created = mapper.readTree(call(json(post("/api/carriers"), carrierBody(null, "acme", List.of(service(null, "std", "Standard")))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.carrierCode").value("ACME"))
                .andExpect(jsonPath("$.services[0].serviceCode").value("STD")).andReturn().getResponse().getContentAsString());
        call(json(post("/api/carriers"), carrierBody(null, "ACME", List.of(service(null, "S", "S")))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errors.carrierCode").exists());
        call(json(post("/api/carriers"), carrierBody(null, "X", List.of()))).andExpect(status().isBadRequest());
        call(json(post("/api/carriers"), carrierBody(null, "X", List.of(service(null, "a", "A"), service(null, "A", "B")))))
                .andExpect(jsonPath("$.errors['services[1].serviceCode']").value("used twice"));

        String url = "/api/carriers/" + created.get("id").asString();
        String std = created.get("services").get(0).get("id").asString();
        call(json(put(url), carrierBody(created.get("version").asLong(), "ACME", List.of(service(std, "STD", "Standard 5-day"), service(null, "EXP", "Express")))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.services", hasSize(2)));
        call(json(put(url), carrierBody(created.get("version").asLong(), "ACME", List.of(service(std, "STD", "Again")))))
                .andExpect(status().isConflict());
        call(delete(url)).andExpect(status().isNoContent());
        call(get(url)).andExpect(status().isNotFound());
    }

    @Test
    void carriersInUseCantBeDeleted() throws Exception {
        packed("SO-1", 1);
        service(only("SO-1"), freight);
        String url = "/api/carriers/" + freight.getId();
        call(delete(url)).andExpect(status().isConflict()).andExpect(jsonPath("$.detail", containsString("1 shipment uses it")));
        JsonNode current = body(call(get(url)));
        call(json(put(url), carrierBody(current.get("version").asLong(), "FREIGHT", List.of(service(null, "NEW", "New")))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.detail", containsString("LTL can't be removed")));
        open(parcel);
        call(delete("/api/carriers/" + parcel.getId())).andExpect(jsonPath("$.detail", containsString("1 manifest uses it")));
    }

    // ---------- Helpers ----------

    private static Map<String, Object> carrierBody(Long version, String code, List<Map<String, Object>> services) {
        Map<String, Object> body = new HashMap<>(Map.of("carrierCode", code, "name", "Acme Freight", "mode", "LTL",
                "trackingRequired", false, "status", "ACTIVE", "services", services));
        body.put("version", version);
        return body;
    }

    private static Map<String, Object> service(String id, String code, String name) {
        Map<String, Object> service = new HashMap<>(Map.of("serviceCode", code, "name", name, "status", "ACTIVE"));
        service.put("id", id);
        return service;
    }

    private String order(String number, int quantity) {
        sync();
        return orderService.create(new OrderRequest(number, "Acme", null, 5,
                List.of(new OrderRequest.Line("SKU-BAR", quantity, null)))).id().toString();
    }

    /** Picks the waiting task into a new carton and drops it; returns the carton's LPN. */
    private String pick() {
        sync();
        String lpn = "OLPN-" + ++nextLpn;
        PickingState state = picking.start(annId, lpn, cartonType.getId());
        while (state.step() == Step.PICK) {
            PickDirective p = state.pick();
            sync();
            state = picking.confirm(annId, new PickingService.Confirm(p.detailId(), p.pickedQuantity(), p.locationId(),
                    p.fromLpnId(), p.item().itemId(), p.serialNumber(), p.remaining(), false));
        }
        sync();
        picking.drop(annId, STATION);
        sync();
        return lpn;
    }

    private PackTaskView start(String lpn) {
        sync();
        return packing.start(annId, STATION, lpn, false);
    }

    private void pack(PackTaskView task, String box, int quantity) {
        sync();
        PackTaskView open = packing.openCarton(annId, task.id(), task.orders().getFirst().id(), cartonType.getId(), box);
        UUID carton = open.cartons().stream().filter(c -> c.status() == CartonStatus.OPEN).findFirst().orElseThrow().id();
        sync();
        packing.pack(annId, task.id(), new PackingService.Pack(carton, open.packedQuantity(), "SKU-BAR", null, null, quantity));
        sync();
        packing.closeCarton(annId, task.id(), carton, null, null);
    }

    /** An order picked into a carton that is packed as it is. Returns the order's id. */
    private String packed(String number, int quantity) {
        String orderId = order(number, quantity);
        PackTaskView task = start(pick());
        sync();
        packing.packAsIs(annId, task.id(), new BigDecimal("2.5"), WeightUom.LB);
        return orderId;
    }

    /** A packed order ready to load with a carrier. */
    private String ready(String number, int quantity, Carrier carrier) throws Exception {
        String orderId = packed(number, quantity);
        address(orderId).andExpect(status().isOk());
        service(only(number), carrier).andExpect(status().isOk());
        return orderId;
    }

    /** An order of 4 packed 1 and 3 into BOX-1 and BOX-2, going by freight. */
    private String twoCartons(String number) throws Exception {
        String orderId = order(number, 4);
        address(orderId);
        PackTaskView task = start(pick());
        pack(task, "BOX-1", 1);
        pack(packing.get(task.id()), "BOX-2", 3);
        service(only(number), freight);
        return orderId;
    }

    private JsonNode only(String orderNumber) throws Exception {
        return body(call(get("/api/shipments").param("q", orderNumber))).get("content").get(0);
    }

    private ResultActions address(String orderId) throws Exception {
        return call(json(put("/api/orders/" + orderId + "/ship-to"),
                Map.of("name", "Acme Retail", "line1", "1 Main St", "city", "Springfield", "state", "IL", "postalCode", "62701", "country", "us")));
    }

    private ResultActions service(JsonNode shipment, Carrier carrier) throws Exception {
        return call(json(put("/api/shipments/" + shipment.get("id").asString() + "/service"),
                Map.of("carrierServiceId", carrier.getServices().getFirst().getId(), "version", shipment.get("version").asLong())));
    }

    private ResultActions tracking(JsonNode shipment, int carton, String number) throws Exception {
        return call(json(put("/api/shipments/" + shipment.get("id").asString() + "/cartons/"
                + shipment.get("cartons").get(carton).get("id").asString() + "/tracking"), Map.of("trackingNumber", number)));
    }

    private JsonNode open(Carrier carrier) throws Exception {
        return body(call(json(post("/api/rf/loading/doors/" + DOOR + "/manifest"), Map.of("carrierId", carrier.getId())))).get("manifest");
    }

    private ResultActions load(JsonNode manifest, String lpn) throws Exception {
        return call(json(post(url(manifest, "load")), Map.of("lpnId", lpn)));
    }

    private ResultActions unload(JsonNode manifest, String lpn) throws Exception {
        return call(json(post(url(manifest, "unload")), Map.of("lpnId", lpn)));
    }

    private ResultActions close(JsonNode manifest, boolean leaveBehind) throws Exception {
        return call(json(post(url(manifest, "close")), Map.of("trailerNumber", "tr-9", "sealNumber", "", "leaveBehind", leaveBehind)));
    }

    private static String url(JsonNode manifest, String action) {
        return "/api/rf/loading/manifests/" + manifest.get("id").asString() + "/" + action;
    }

    private void hold(String lpn, InventoryStatus status) {
        inventory.findByContainer_IdIn(List.of(container(lpn).getId())).getFirst().setStatus(status);
        sync();
    }

    private ResultActions call(MockHttpServletRequestBuilder request) throws Exception {
        sync();
        return mvc.perform(request.cookie(ann));
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, Object body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
    }

    private JsonNode body(ResultActions result) throws Exception {
        return mapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    /** Stores pending changes and forgets loaded entities, so each request reads the database like a new one. */
    private void sync() {
        em.flush();
        em.clear();
    }

    private Container container(String lpn) {
        sync();
        return containers.findByLpnId(lpn).orElseThrow();
    }

    private Carrier carrier(String code, String name, boolean tracking, String service) {
        Carrier c = new Carrier(UUID.randomUUID());
        c.setCarrierCode(code);
        c.setName(name);
        c.setMode(CarrierMode.PARCEL);
        c.setTrackingRequired(tracking);
        c.setStatus(ActiveStatus.ACTIVE);
        c.getServices().add(new CarrierService(UUID.randomUUID(), c, service, service));
        return carriers.save(c);
    }

    private Location location(String code, LocationType type, String zone, String position) {
        boolean storage = type == LocationType.STORAGE;
        Location l = new Location(UUID.randomUUID());
        l.setLocationId(code);
        l.setType(type);
        l.setArea("DC1");
        l.setZone(zone);
        l.setAisle(storage ? "01" : null);
        l.setBay(storage ? "01" : null);
        l.setLevel(storage ? "A" : null);
        l.setPosition(position);
        l.setWidth(BigDecimal.TEN);
        l.setDepth(BigDecimal.TEN);
        l.setHeight(BigDecimal.TEN);
        l.setDimensionUom(DimensionUom.IN);
        return locations.save(l);
    }
}
