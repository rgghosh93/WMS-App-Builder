package com.wms.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
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
import com.wms.inventory.InventoryRecord;
import com.wms.inventory.InventoryRepository;
import com.wms.inventory.InventoryStatus;
import com.wms.masterdata.container.Container;
import com.wms.masterdata.container.ContainerCategory;
import com.wms.masterdata.container.ContainerRepository;
import com.wms.masterdata.container.ContainerStatus;
import com.wms.masterdata.container.ContainerType;
import com.wms.masterdata.container.ContainerTypeRepository;
import com.wms.masterdata.item.Item;
import com.wms.masterdata.item.ItemRepository;
import com.wms.masterdata.item.ItemUom;
import com.wms.masterdata.item.ItemUomRepository;
import com.wms.masterdata.location.Location;
import com.wms.masterdata.location.LocationRepository;
import com.wms.masterdata.location.LocationType;
import com.wms.masterdata.uom.UnitOfMeasure;
import com.wms.masterdata.uom.UnitOfMeasureRepository;
import com.wms.masterdata.uom.UomKind;
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
class PackingTest {

    private static final String PIN = "1357";
    private static final String STATION = "DC1-PACK-01";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private EntityManager em;
    @Autowired private ItemRepository items;
    @Autowired private ItemUomRepository itemUoms;
    @Autowired private UnitOfMeasureRepository uoms;
    @Autowired private LocationRepository locations;
    @Autowired private InventoryRepository inventory;
    @Autowired private ContainerRepository containers;
    @Autowired private ContainerTypeRepository types;
    @Autowired private TestUsers people;
    @Autowired private OrderService orderService;
    @Autowired private PickingService picking;

    private Item bars;
    private ContainerType cartonType;
    private UUID annId;
    private Cookie ann;
    private Cookie bob;

    /** Bars in two lots (10 of OLD expiring first, 40 of NEW), 6 to a case; a serial-tracked drill; totes T1 and T2. */
    @BeforeEach
    void fixtures() throws Exception {
        Location shelfA = location("DC1-BK-01-01-A-01", LocationType.STORAGE, "01");
        Location shelfB = location("DC1-BK-01-01-A-02", LocationType.STORAGE, "02");
        location(STATION, LocationType.PACKING, "01");
        location("DC1-PACK-02", LocationType.PACKING, "02");

        bars = item("SKU-BAR", "111000");
        bars.setLotControlled(true);
        UnitOfMeasure cs = new UnitOfMeasure(UUID.randomUUID());
        cs.setUomCode("CS");
        cs.setDescription("Case");
        cs.setKind(UomKind.CASE);
        cs.setStatus(ActiveStatus.ACTIVE);
        cs = uoms.save(cs);
        ItemUom barCase = new ItemUom(UUID.randomUUID(), bars, cs);
        barCase.setQuantity(6);
        barCase.setBarcode("111006");
        itemUoms.save(barCase);
        stock(bars, 10, "OLD", null, shelfA, "2026-11-01T10:00:00Z");
        stock(bars, 40, "NEW", null, shelfB, "2026-11-02T10:00:00Z");
        Item drill = item("SKU-DRILL", "222000");
        drill.setSerialControlled(true);
        stock(drill, 1, null, "D-1", shelfA, "2026-11-01T10:00:00Z");
        stock(drill, 1, null, "D-2", shelfA, "2026-11-02T10:00:00Z");

        cartonType = type("CTN-M", ContainerCategory.CARTON, false);
        ContainerType tote = type("TOTE", ContainerCategory.TOTE, true);
        containers.save(tote("T1", tote));
        containers.save(tote("T2", tote));

        annId = people.operator("ANN", "Ann Lee", PIN).getId();
        people.operator("BOB", "Bob Ray", PIN);
        ann = signIn("ann");
        bob = signIn("bob");
    }

    // ---------- The task ----------

    @Test
    void droppingAContainerCreatesItsPackingTask() throws Exception {
        String orderId = order("SO-1", line("SKU-BAR", 12), line("SKU-DRILL", 1));
        pickAndDrop("T1", STATION);

        JsonNode task = body(call(get("/api/pack-tasks"), null)
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].taskNumber", matchesPattern("PK\\d{6}")))
                .andExpect(jsonPath("$.content[0].status").value("OPEN"))
                .andExpect(jsonPath("$.content[0].station.locationId").value(STATION))
                .andExpect(jsonPath("$.content[0].container.lpnId").value("T1"))
                .andExpect(jsonPath("$.content[0].totalQuantity").value(13))
                .andExpect(jsonPath("$.content[0].details", hasSize(3)))
                .andExpect(jsonPath("$.content[0].canPackAsIs").value(false))).get("content").get(0);
        call(get("/api/pack-tasks/" + task.get("id").asString()), null).andExpect(jsonPath("$.orders[0].orderNumber").value("SO-1"));
        call(get("/api/pack-tasks/" + UUID.randomUUID()), null).andExpect(status().isNotFound());
        for (String q : List.of("t1", "so-1", "acme", "sku-drill")) {
            call(get("/api/pack-tasks").param("q", q), null).andExpect(jsonPath("$.totalElements").value(1));
        }
        call(get("/api/pack-tasks").param("q", "nope"), null).andExpect(jsonPath("$.totalElements").value(0));
        call(get("/api/pack-tasks").param("status", "COMPLETED"), null).andExpect(jsonPath("$.totalElements").value(0));
        call(get("/api/pack-tasks/stations"), null)
                .andExpect(jsonPath("$[0].waitingTasks").value(1))
                .andExpect(jsonPath("$[1].waitingTasks").value(0));
        call(get("/api/orders/" + orderId), null)
                .andExpect(jsonPath("$.packTasks", hasSize(1)))
                .andExpect(jsonPath("$.events[*].message", hasItem(containsString("Created packing task"))));
        call(get("/api/rf/packing/stations/" + STATION), ann).andExpect(jsonPath("$.tasks", hasSize(1)));
        mvc.perform(get("/api/rf/packing/stations")).andExpect(status().isUnauthorized());
    }

    @Test
    void theContainerMustBeWaitingAtThisStation() throws Exception {
        order("SO-1", line("SKU-BAR", 1));
        pickAndDrop("T1", STATION);
        start(ann, "DC1-PACK-02", "T1").andExpect(jsonPath("$.errors.lpnId").value("T1 was dropped at " + STATION));
        start(ann, STATION, "T2").andExpect(jsonPath("$.errors.lpnId").value("T2 has nothing to pack"));
        start(ann, STATION, "NOPE").andExpect(status().isBadRequest());
        start(ann, "DC1-BK-01-01-A-01", "T1").andExpect(jsonPath("$.errors.locationId", containsString("not a pack station")));
        start(ann, STATION, "t1").andExpect(status().isOk()).andExpect(jsonPath("$.assignedTo").value("Ann Lee"));
    }

    @Test
    void anotherPackerCanTakeOver() throws Exception {
        order("SO-1", line("SKU-BAR", 1));
        pickAndDrop("T1", STATION);
        JsonNode task = body(start(ann, STATION, "T1"));
        start(ann, STATION, "T1").andExpect(status().isOk());
        start(bob, STATION, "T1").andExpect(status().isConflict()).andExpect(jsonPath("$.errors.assignedTo").value("Ann Lee"));
        carton(bob, task, 0, null).andExpect(status().isConflict());
        call(json(post("/api/rf/packing/start"), Map.of("locationId", STATION, "lpnId", "T1", "takeOver", true)), bob)
                .andExpect(jsonPath("$.assignedTo").value("Bob Ray"));
        carton(ann, task, 0, null).andExpect(status().isConflict());
        carton(bob, task, 0, null).andExpect(status().isOk());
    }

    // ---------- Packing ----------

    @Test
    void packsItemsIntoCartonsAndCompletesTheOrder() throws Exception {
        String orderId = order("SO-1", line("SKU-BAR", 12), line("SKU-DRILL", 1));
        pickAndDrop("T1", STATION);
        JsonNode task = body(start(ann, STATION, "T1"));

        task = body(carton(ann, task, 0, "box-1")
                .andExpect(jsonPath("$.cartons[0].container.lpnId").value("BOX-1"))
                .andExpect(jsonPath("$.cartons[0].status").value("OPEN")));
        // Two lots: the packer says which. A case of NEW isn't there (only 2 EA), so eaches.
        pack(ann, task, 0, "111000", 1, null, null).andExpect(jsonPath("$.errors.lotNumber").exists());
        task = body(pack(ann, task, 0, "111006", 1, null, "OLD").andExpect(jsonPath("$.packedQuantity").value(6)));
        task = body(pack(ann, task, 0, "111000", 4, null, "old").andExpect(jsonPath("$.packedQuantity").value(10)));
        call(get("/api/orders/" + orderId), null).andExpect(jsonPath("$.status").value("PICKED"));
        task = body(close(ann, task, 0, "4.25", "LB")
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.cartons[0].status").value("CLOSED"))
                .andExpect(jsonPath("$.cartons[0].weight").value(4.25))
                .andExpect(jsonPath("$.cartons[0].closedBy").value("Ann Lee")));
        call(get("/api/orders/" + orderId), null)
                .andExpect(jsonPath("$.status").value("PACKING"))
                .andExpect(jsonPath("$.lines[0].packedQuantity").value(10));

        // A second carton, with a label made here. Only one lot is left, so no lot is asked for.
        task = body(carton(ann, task, 0, null).andExpect(jsonPath("$.cartons[1].container.lpnId", matchesPattern("CT\\d{8}"))));
        task = body(pack(ann, task, 1, "sku-bar", 2, null, null));
        task = body(pack(ann, task, 1, "222000", 1, "d-1", null).andExpect(jsonPath("$.packedQuantity").value(13)));
        close(ann, task, 1, null, null).andExpect(jsonPath("$.status").value("COMPLETED"));

        sync();
        assertThat(inventory.sumQuantityByItem(bars.getId())).isEqualTo(50);
        Container box = container("BOX-1");
        assertThat(box.getCurrentLocation().getLocationId()).isEqualTo(STATION);
        assertThat(inventory.findByContainer_IdIn(List.of(box.getId()))).extracting(r -> r.getLotNumber() + r.getQuantity()).containsExactly("OLD10");
        // The tote is empty and ready for the next pick.
        assertThat(container("T1").getStatus()).isEqualTo(ContainerStatus.AVAILABLE);
        assertThat(inventory.countByContainer_Id(container("T1").getId())).isZero();

        call(get("/api/orders/" + orderId), null)
                .andExpect(jsonPath("$.status").value("PACKED"))
                .andExpect(jsonPath("$.lines[*].status", everyItem(is("PACKED"))))
                .andExpect(jsonPath("$.allocations[*].quantity", hasSize(3)))
                .andExpect(jsonPath("$.allocations[*].task.taskNumber", everyItem(notNullValue())))
                .andExpect(jsonPath("$.allocations[?(@.quantity == 10)].container.lpnId").value("BOX-1"))
                .andExpect(jsonPath("$.packTasks[0].cartons", hasSize(2)))
                .andExpect(jsonPath("$.events[?(@.type == 'PACKED')].message", hasItem(containsString("Ann Lee packed 10 EA into carton BOX-1, 4.25 lb"))));
        call(get("/api/allocations").param("q", "box-1"), null).andExpect(jsonPath("$.totalElements").value(1));
        call(get("/api/pack-tasks").param("q", "box-1"), null).andExpect(jsonPath("$.totalElements").value(1));
        call(get("/api/pack-tasks/stations"), null).andExpect(jsonPath("$[0].waitingTasks").value(0));
        start(ann, STATION, "T1").andExpect(status().isBadRequest());
        // Packed stock is spoken for.
        String other = order("SO-2", line("SKU-BAR", 50));
        call(get("/api/orders/" + other), null).andExpect(jsonPath("$.lines[0].allocatedQuantity").value(38));
        call(post("/api/orders/" + orderId + "/cancel"), null).andExpect(status().isConflict());
    }

    @Test
    void wrongScansPackNothing() throws Exception {
        order("SO-1", line("SKU-BAR", 2), line("SKU-DRILL", 1));
        pickAndDrop("T1", STATION);
        JsonNode task = body(start(ann, STATION, "T1"));
        task = body(carton(ann, task, 0, null));
        pack(ann, task, 0, "999", 1, null, null).andExpect(jsonPath("$.errors.itemBarcode", containsString("not in T1")));
        pack(ann, task, 0, "111000", 3, null, null).andExpect(jsonPath("$.errors.quantity", containsString("only 2 EA")));
        pack(ann, task, 0, "111006", 1, null, null).andExpect(jsonPath("$.errors.quantity").exists());
        pack(ann, task, 0, "111000", 0, null, null).andExpect(jsonPath("$.errors.quantity").exists());
        pack(ann, task, 0, "111000", 1, null, "NEW").andExpect(jsonPath("$.errors.lotNumber").exists());
        pack(ann, task, 0, "222000", 1, null, null).andExpect(jsonPath("$.errors.serialNumber").value("Scan the serial number"));
        pack(ann, task, 0, "222000", 1, "D-2", null).andExpect(jsonPath("$.errors.serialNumber").exists());
        close(ann, task, 0, null, null).andExpect(status().isConflict());
        task = body(pack(ann, task, 0, "111000", 2, null, null));
        pack(ann, task, 0, "111000", 1, null, null).andExpect(jsonPath("$.errors.itemBarcode", containsString("No more SKU-BAR")));
        close(ann, task, 0, "-1", "LB").andExpect(jsonPath("$.errors.weight").exists());
        close(ann, task, 0, "1.5", null).andExpect(jsonPath("$.errors.weightUom").exists());
    }

    @Test
    void aRepeatedScanIsNotPackedTwice() throws Exception {
        order("SO-1", line("SKU-BAR", 5));
        pickAndDrop("T1", STATION);
        JsonNode task = body(start(ann, STATION, "T1"));
        task = body(carton(ann, task, 0, null));
        pack(ann, task, 0, "111000", 1, null, null).andExpect(jsonPath("$.packedQuantity").value(1));
        pack(ann, task, 0, "111000", 1, null, null).andExpect(status().isConflict());
        call(get("/api/rf/packing/tasks/" + task.get("id").asString()), ann).andExpect(jsonPath("$.packedQuantity").value(1));
    }

    @Test
    void cartonsNeedAFreeLabelAndACartonType() throws Exception {
        order("SO-1", line("SKU-BAR", 2));
        pickAndDrop("T1", STATION);
        JsonNode task = body(start(ann, STATION, "T1"));
        carton(ann, task, 0, "T2").andExpect(jsonPath("$.errors.lpnId", containsString("already used")));
        carton(ann, task, 0, "111000").andExpect(jsonPath("$.errors.lpnId", containsString("is an item")));
        carton(ann, task, 0, STATION).andExpect(jsonPath("$.errors.lpnId", containsString("is a location")));
        String url = "/api/rf/packing/tasks/" + task.get("id").asString() + "/cartons";
        call(json(post(url), Map.of("orderId", task.get("orders").get(0).get("id").asString())), ann)
                .andExpect(jsonPath("$.errors.containerTypeId").exists());
        call(json(post(url), Map.of("orderId", UUID.randomUUID(), "containerTypeId", cartonType.getId())), ann)
                .andExpect(jsonPath("$.errors.orderId").exists());
        carton(ann, task, 0, "BOX-1").andExpect(status().isOk());
        carton(ann, task, 0, "BOX-2").andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value("Close BOX-1 first."));
    }

    @Test
    void emptyingACartonPutsEverythingBack() throws Exception {
        order("SO-1", line("SKU-BAR", 5), line("SKU-DRILL", 1));
        pickAndDrop("T1", STATION);
        JsonNode task = body(start(ann, STATION, "T1"));
        task = body(carton(ann, task, 0, "BOX-1"));
        task = body(pack(ann, task, 0, "111000", 2, null, null));
        task = body(pack(ann, task, 0, "222000", 1, "D-1", null));
        task = body(empty(ann, task, 0).andExpect(jsonPath("$.packedQuantity").value(0)).andExpect(jsonPath("$.cartons", hasSize(0))));
        sync();
        assertThat(containers.findByLpnId("BOX-1")).isEmpty();
        assertThat(inventory.findByContainer_IdIn(List.of(container("T1").getId()))).extracting(InventoryRecord::getQuantity)
                .containsExactlyInAnyOrder(5, 1);

        // All of a detail packed, then taken out, then packed again.
        task = body(carton(ann, task, 0, "BOX-1"));
        task = body(pack(ann, task, 0, "111000", 3, null, null));
        task = body(pack(ann, task, 0, "111000", 2, null, null));
        task = body(empty(ann, task, 0));
        task = body(carton(ann, task, 0, "BOX-1"));
        task = body(pack(ann, task, 0, "111000", 5, null, null));
        task = body(pack(ann, task, 0, "222000", 1, "D-1", null));
        close(ann, task, 0, null, null).andExpect(jsonPath("$.status").value("COMPLETED"));
        sync();
        assertThat(inventory.sumQuantityByItem(bars.getId())).isEqualTo(50);
    }

    @Test
    void batchContainersArePackedPerOrder() throws Exception {
        UUID a = received("SO-A", line("SKU-BAR", 2));
        UUID b = received("SO-B", line("SKU-BAR", 3));
        orderService.allocateBatch(List.of(a, b));
        pickAndDrop("T1", STATION);
        JsonNode task = body(start(ann, STATION, "T1").andExpect(jsonPath("$.orders", hasSize(2))));
        // A carton for each order can be open at once, and only takes its own order's items.
        task = body(carton(ann, task, 0, "BOX-A"));
        task = body(carton(ann, task, 1, "BOX-B"));
        pack(ann, task, 0, "111000", 3, null, null).andExpect(jsonPath("$.errors.quantity", containsString("only 2 EA")));
        task = body(pack(ann, task, 0, "111000", 2, null, null));
        task = body(pack(ann, task, 1, "111000", 3, null, null));
        task = body(close(ann, task, 0, null, null).andExpect(jsonPath("$.status").value("IN_PROGRESS")));
        call(get("/api/orders/" + a), null).andExpect(jsonPath("$.status").value("PACKED"));
        call(get("/api/orders/" + b), null).andExpect(jsonPath("$.status").value("PICKED"));
        close(ann, task, 1, null, null).andExpect(jsonPath("$.status").value("COMPLETED"));
        call(get("/api/orders/" + b), null).andExpect(jsonPath("$.status").value("PACKED"));
    }

    @Test
    void aCartonPickedForOneOrderCanShipAsItIs() throws Exception {
        String orderId = order("SO-1", line("SKU-BAR", 12));
        pickAndDrop("OLPN-1", STATION);
        JsonNode task = body(start(ann, STATION, "OLPN-1").andExpect(jsonPath("$.canPackAsIs").value(true)));
        asIs(ann, task, "3.5", "KG")
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.cartons[0].container.lpnId").value("OLPN-1"))
                .andExpect(jsonPath("$.cartons[0].totalQuantity").value(12));
        assertThat(container("OLPN-1").getStatus()).isEqualTo(ContainerStatus.IN_USE);
        call(get("/api/orders/" + orderId), null).andExpect(jsonPath("$.status").value("PACKED"))
                .andExpect(jsonPath("$.allocations[*].container.lpnId", everyItem(is("OLPN-1"))));
    }

    @Test
    void totesAndStartedCartonsCantShipAsTheyAre() throws Exception {
        order("SO-1", line("SKU-BAR", 1));
        pickAndDrop("T1", STATION);
        asIs(ann, body(start(ann, STATION, "T1")), null, null).andExpect(status().isConflict());

        order("SO-2", line("SKU-BAR", 1));
        pickAndDrop("OLPN-2", STATION);
        JsonNode task = body(start(ann, STATION, "OLPN-2"));
        task = body(carton(ann, task, 0, null).andExpect(jsonPath("$.canPackAsIs").value(false)));
        asIs(ann, task, null, null).andExpect(status().isConflict());
        // Repacked into another carton, the picking carton is finished with.
        task = body(pack(ann, task, 0, "111000", 1, null, null));
        close(ann, task, 0, null, null).andExpect(jsonPath("$.status").value("COMPLETED"));
        assertThat(container("OLPN-2").getStatus()).isEqualTo(ContainerStatus.RETIRED);
    }

    @Test
    void missingStockGoesOnHoldAndTheLineNeedsItAgain() throws Exception {
        String orderId = order("SO-1", line("SKU-BAR", 5), line("SKU-DRILL", 1));
        pickAndDrop("T1", STATION);
        JsonNode task = body(start(ann, STATION, "T1"));
        task = body(carton(ann, task, 0, null));
        task = body(pack(ann, task, 0, "111000", 3, null, null));
        missing(ann, task, 0, 5).andExpect(status().isConflict());
        task = body(missing(ann, task, 0, 2)
                .andExpect(jsonPath("$.shortQuantity").value(2))
                .andExpect(jsonPath("$.details[0].remaining").value(0)));
        task = body(pack(ann, task, 0, "222000", 1, "D-1", null));
        close(ann, task, 0, null, null).andExpect(jsonPath("$.status").value("COMPLETED"));

        sync();
        Location station = locations.findByLocationId(STATION).orElseThrow();
        assertThat(inventory.findByLocation_IdIn(List.of(station.getId()))).extracting(r -> r.getStatus() + " " + r.getQuantity())
                .containsExactly(InventoryStatus.ON_HOLD + " 2");
        assertThat(container("T1").getStatus()).isEqualTo(ContainerStatus.AVAILABLE);
        call(get("/api/orders/" + orderId), null)
                .andExpect(jsonPath("$.status").value("PACKING"))
                .andExpect(jsonPath("$.lines[0].allocatedQuantity").value(3))
                .andExpect(jsonPath("$.lines[0].packedQuantity").value(3))
                .andExpect(jsonPath("$.events[?(@.type == 'SHORT')].message", hasItem(containsString("2 EA of SKU-BAR missing at packing"))));
        // The rest is allocated, picked and packed on new tasks.
        call(post("/api/orders/" + orderId + "/allocate"), null).andExpect(jsonPath("$.lines[0].allocatedQuantity").value(5));
        pickAndDrop("T2", STATION);
        task = body(start(ann, STATION, "T2"));
        task = body(carton(ann, task, 0, null));
        task = body(pack(ann, task, 0, "111000", 2, null, null));
        close(ann, task, 0, null, null);
        call(get("/api/orders/" + orderId), null).andExpect(jsonPath("$.status").value("PACKED"));
    }

    @Test
    void nothingToPackCompletesTheTask() throws Exception {
        String orderId = order("SO-1", line("SKU-DRILL", 1));
        pickAndDrop("T1", STATION);
        JsonNode task = body(start(ann, STATION, "T1"));
        missing(ann, task, 0, 1).andExpect(jsonPath("$.status").value("COMPLETED"));
        assertThat(container("T1").getStatus()).isEqualTo(ContainerStatus.AVAILABLE);
        call(get("/api/orders/" + orderId), null).andExpect(jsonPath("$.status").value("BACKORDERED"));
    }

    @Test
    void stockThatExpiresAfterPickingCantBePacked() throws Exception {
        order("SO-1", line("SKU-BAR", 2));
        pickAndDrop("T1", STATION);
        JsonNode task = body(start(ann, STATION, "T1"));
        task = body(carton(ann, task, 0, null));
        inventory.findByContainer_IdIn(List.of(container("T1").getId())).getFirst().setExpiryDate(java.time.LocalDate.now().minusDays(1));
        pack(ann, task, 0, "111000", 1, null, null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("has expired")));
    }

    @Test
    void stockPutOnHoldAfterPickingCantBePacked() throws Exception {
        order("SO-1", line("SKU-BAR", 2));
        pickAndDrop("T1", STATION);
        JsonNode task = body(start(ann, STATION, "T1"));
        task = body(carton(ann, task, 0, null));
        inventory.findByContainer_IdIn(List.of(container("T1").getId())).getFirst().setStatus(InventoryStatus.ON_HOLD);
        pack(ann, task, 0, "111000", 1, null, null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("on hold")));
    }

    // ---------- Helpers ----------

    /** Picks the next waiting task into a container, scanning everything as directed, and drops it. */
    private void pickAndDrop(String lpn, String station) {
        sync();
        PickingState state = picking.start(annId, lpn, containers.findByLpnId(lpn).isPresent() ? null : cartonType.getId());
        while (state.step() == Step.PICK) {
            PickDirective p = state.pick();
            sync();
            state = picking.confirm(annId, new PickingService.Confirm(p.detailId(), p.pickedQuantity(), p.locationId(),
                    p.fromLpnId(), p.item().itemId(), p.serialNumber(), p.remaining(), false));
        }
        sync();
        picking.drop(annId, station);
    }

    private ResultActions start(Cookie user, String station, String lpn) throws Exception {
        return call(json(post("/api/rf/packing/start"), Map.of("locationId", station, "lpnId", lpn)), user);
    }

    private ResultActions carton(Cookie user, JsonNode task, int order, String lpn) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("orderId", task.get("orders").get(order).get("id").asString());
        body.put("containerTypeId", cartonType.getId());
        body.put("lpnId", lpn);
        return call(json(post(url(task, "cartons")), body), user);
    }

    private ResultActions pack(Cookie user, JsonNode task, int carton, String barcode, int quantity, String serial, String lot) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("cartonId", cartonId(task, carton));
        body.put("packedSoFar", task.get("packedQuantity").asInt());
        body.put("itemBarcode", barcode);
        body.put("quantity", quantity);
        body.put("serialNumber", serial);
        body.put("lotNumber", lot);
        return call(json(post(url(task, "pack")), body), user);
    }

    private ResultActions close(Cookie user, JsonNode task, int carton, String weight, String uom) throws Exception {
        return call(json(post(url(task, "cartons/" + cartonId(task, carton) + "/close")), weight(weight, uom)), user);
    }

    private ResultActions asIs(Cookie user, JsonNode task, String weight, String uom) throws Exception {
        return call(json(post(url(task, "pack-as-is")), weight(weight, uom)), user);
    }

    private ResultActions empty(Cookie user, JsonNode task, int carton) throws Exception {
        return call(post(url(task, "cartons/" + cartonId(task, carton) + "/empty")), user);
    }

    private ResultActions missing(Cookie user, JsonNode task, int detail, int remaining) throws Exception {
        return call(json(post(url(task, "missing")),
                Map.of("detailId", task.get("details").get(detail).get("id").asString(), "remaining", remaining)), user);
    }

    private static Map<String, Object> weight(String weight, String uom) {
        Map<String, Object> body = new HashMap<>();
        body.put("weight", weight == null ? null : new BigDecimal(weight));
        body.put("weightUom", uom);
        return body;
    }

    private static String url(JsonNode task, String path) {
        return "/api/rf/packing/tasks/" + task.get("id").asString() + "/" + path;
    }

    private static String cartonId(JsonNode task, int carton) {
        return task.get("cartons").get(carton).get("id").asString();
    }

    private Cookie signIn(String user) throws Exception {
        sync();
        return mvc.perform(json(post("/api/rf/session"), Map.of("userCode", user, "pin", PIN)))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("wms_device");
    }

    private ResultActions call(MockHttpServletRequestBuilder request, Cookie user) throws Exception {
        sync();
        return mvc.perform(user == null ? request : request.cookie(user));
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

    private static OrderRequest.Line line(String item, int quantity) {
        return new OrderRequest.Line(item, quantity, null);
    }

    private String order(String number, OrderRequest.Line... lines) {
        sync();
        return orderService.create(new OrderRequest(number, "Acme", null, 5, List.of(lines))).id().toString();
    }

    private UUID received(String number, OrderRequest.Line... lines) {
        return orderService.create(new OrderRequest(number, "Acme", null, 5, List.of(lines)), false).id();
    }

    private Item item(String code, String barcode) {
        Item i = new Item(UUID.randomUUID());
        i.setItemId(code);
        i.setDescription(code);
        i.setStatus(ActiveStatus.ACTIVE);
        i.setBarcode(barcode);
        return items.save(i);
    }

    private Location location(String code, LocationType type, String position) {
        boolean storage = type == LocationType.STORAGE;
        Location l = new Location(UUID.randomUUID());
        l.setLocationId(code);
        l.setType(type);
        l.setArea("DC1");
        l.setZone(storage ? "BK" : "PACK");
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

    private ContainerType type(String code, ContainerCategory category, boolean reusable) {
        ContainerType t = new ContainerType(UUID.randomUUID());
        t.setContainerTypeId(code);
        t.setDescription(code);
        t.setCategory(category);
        t.setStatus(ActiveStatus.ACTIVE);
        t.setReusable(reusable);
        t.setWidth(BigDecimal.ONE);
        t.setDepth(BigDecimal.ONE);
        t.setHeight(BigDecimal.ONE);
        t.setDimensionUom(DimensionUom.IN);
        return types.save(t);
    }

    private static Container tote(String code, ContainerType type) {
        Container c = new Container(UUID.randomUUID());
        c.setLpnId(code);
        c.setContainerType(type);
        c.setStatus(ContainerStatus.AVAILABLE);
        c.setPermanent(true);
        return c;
    }

    private void stock(Item item, int quantity, String lot, String serial, Location location, String receivedAt) {
        InventoryRecord r = new InventoryRecord(UUID.randomUUID(), item, quantity);
        r.setLotNumber(lot);
        r.setSerialNumber(serial);
        r.setReceivedAt(Instant.parse(receivedAt));
        r.placeIn(location);
        inventory.save(r);
    }
}
