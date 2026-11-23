package com.wms.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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
import com.wms.users.AppUserRepository;
import com.wms.users.TestUsers;

import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PickingTest {

    private static final String PIN = "1357";

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
    @Autowired private AppUserRepository users;
    @Autowired private TestUsers people;
    @Autowired private OrderService orderService;

    private Location shelf;
    private Item bars;
    private ContainerType cartonType;
    private Cookie ann;
    private Cookie bob;

    /**
     * Bars: 40 EA loose on shelf A-01, with an EA barcode and a 6-EA case barcode.
     * Drill: serial D-1 in carton BOX-1 on a pallet in reserve. Totes T1 and T2. Two pack stations.
     */
    @BeforeEach
    void fixtures() throws Exception {
        shelf = location("DC1-BK-01-01-A-01", LocationType.STORAGE, "BK", "01", "01", "A", "01");
        Location reserve = location("DC1-RS-01-01-A-01", LocationType.STORAGE, "RS", "01", "01", "A", "01");
        location("DC1-PACK-01", LocationType.PACKING, "PACK", null, null, null, "01");
        location("DC1-PACK-02", LocationType.PACKING, "PACK", null, null, null, "02");

        bars = item("SKU-BAR", "111000");
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
        stock(bars, 40, null, shelf, null);

        Item drill = item("SKU-DRILL", "222000");
        drill.setSerialControlled(true);
        ContainerType pallet = type("PLT", ContainerCategory.PALLET, true);
        cartonType = type("CTN-M", ContainerCategory.CARTON, false);
        ContainerType tote = type("TOTE", ContainerCategory.TOTE, true);
        ContainerType bin = type("BIN", ContainerCategory.BIN, true);
        Container p1 = lpn("P1", pallet, ContainerStatus.IN_USE, false);
        p1.setCurrentLocation(reserve);
        p1 = containers.save(p1);
        Container box = lpn("BOX-1", cartonType, ContainerStatus.IN_USE, false);
        box.setParent(p1);
        box = containers.save(box);
        stock(drill, 1, "D-1", null, box);
        containers.save(lpn("T1", tote, ContainerStatus.AVAILABLE, true));
        containers.save(lpn("T2", tote, ContainerStatus.AVAILABLE, true));
        containers.save(lpn("B1", bin, ContainerStatus.AVAILABLE, true));

        people.operator("ANN", "Ann Lee", PIN);
        people.operator("BOB", "Bob Ray", PIN);
        ann = signIn("ann");
        bob = signIn("BOB");
    }

    // ---------- Sign-in ----------

    @Test
    void pickingNeedsASignedInUser() throws Exception {
        mvc.perform(get("/api/rf/picking")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/rf/picking").cookie(new Cookie("wms_device", "made-up"))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/rf/session").cookie(ann))
                .andExpect(jsonPath("$.displayName").value("Ann Lee"))
                .andExpect(jsonPath("$.pinHash").doesNotExist());
        mvc.perform(delete("/api/rf/session").cookie(ann)).andExpect(status().isNoContent());
        mvc.perform(get("/api/rf/picking").cookie(ann)).andExpect(status().isUnauthorized());
    }

    @Test
    void theSessionCookieCantBeReadByScripts() throws Exception {
        signInRequest("ann", PIN)
                .andExpect(cookie().httpOnly("wms_device", true))
                .andExpect(cookie().attribute("wms_device", "SameSite", "Strict"));
    }

    @Test
    void wrongPinsAreRefusedThenLockedOut() throws Exception {
        signInRequest("nobody", PIN).andExpect(status().isUnauthorized());
        for (int i = 0; i < 5; i++) {
            signInRequest("ann", "0000").andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.detail").value("User ID or PIN is not right"));
        }
        signInRequest("ann", PIN).andExpect(status().isTooManyRequests());
        signInRequest("bob", PIN).andExpect(status().isOk());
    }

    @Test
    void inactiveUsersCantSignInOrCarryOn() throws Exception {
        users.findByUserCode("ANN").orElseThrow().setStatus(ActiveStatus.INACTIVE);
        signInRequest("ann", PIN).andExpect(status().isUnauthorized());
        call(get("/api/rf/picking"), ann).andExpect(status().isUnauthorized());
    }

    // ---------- Starting ----------

    @Test
    void scanningAToteAssignsTheMostUrgentTask() throws Exception {
        order("SO-LOW", 7, line("SKU-BAR", 1));
        order("SO-HIGH", 1, line("SKU-BAR", 2));

        call(get("/api/rf/picking"), ann)
                .andExpect(jsonPath("$.step").value("SCAN_CONTAINER"))
                .andExpect(jsonPath("$.waitingTasks").value(2));
        start(ann, "t1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.step").value("PICK"))
                .andExpect(jsonPath("$.task.toLpnId").value("T1"))
                .andExpect(jsonPath("$.task.detailCount").value(1))
                .andExpect(jsonPath("$.pick.orderNumber").value("SO-HIGH"))
                .andExpect(jsonPath("$.pick.locationId").value("DC1-BK-01-01-A-01"));
        // The next picker gets the other one; the same tote or a second task is refused.
        start(bob, "T1").andExpect(status().isConflict()).andExpect(jsonPath("$.errors.assignedTo").value("Ann Lee"));
        start(bob, "T2").andExpect(jsonPath("$.pick.orderNumber").value("SO-LOW"));
        start(ann, "T2").andExpect(status().isConflict());
        assertThat(container("T1").getStatus()).isEqualTo(ContainerStatus.IN_USE);
    }

    @Test
    void onlyEmptyOutboundContainersCanBeUsed() throws Exception {
        order("SO-1", 5, line("SKU-BAR", 1));
        start(ann, "BOX-1").andExpect(status().isBadRequest());
        start(ann, "B1").andExpect(jsonPath("$.errors.lpnId", containsString("storage bin")));
        start(ann, "DC1-PACK-01").andExpect(jsonPath("$.errors.lpnId", containsString("is a location")));
        start(ann, "111006").andExpect(jsonPath("$.errors.lpnId", containsString("is an item")));
        start(ann, "  ").andExpect(status().isBadRequest());
        container("T1").setStatus(ContainerStatus.ON_HOLD);
        start(ann, "T1").andExpect(jsonPath("$.errors.lpnId", containsString("on hold")));
    }

    @Test
    void aNewCartonLabelAsksForItsTypeAndBecomesAContainer() throws Exception {
        order("SO-1", 5, line("SKU-BAR", 1));
        start(ann, "olpn-1")
                .andExpect(jsonPath("$.step").value("CHOOSE_CONTAINER_TYPE"))
                .andExpect(jsonPath("$.newContainer.lpnId").value("OLPN-1"))
                .andExpect(jsonPath("$.newContainer.types", hasSize(1)))
                .andExpect(jsonPath("$.newContainer.types[0].containerTypeId").value("CTN-M"));
        assertThat(containers.findByLpnId("OLPN-1")).isEmpty();

        call(json(post("/api/rf/picking/start"), Map.of("lpnId", "olpn-1", "containerTypeId", cartonType.getId())), ann)
                .andExpect(jsonPath("$.step").value("PICK"))
                .andExpect(jsonPath("$.task.toLpnId").value("OLPN-1"));
        assertThat(container("OLPN-1").isPermanent()).isFalse();
    }

    @Test
    void nothingIsTakenWhenNoTaskIsWaiting() throws Exception {
        start(ann, "T1").andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value("No pick tasks are waiting."));
        assertThat(container("T1").getStatus()).isEqualTo(ContainerStatus.AVAILABLE);
    }

    @Test
    void aTaskCanBeGivenBackUntilSomethingIsPicked() throws Exception {
        order("SO-1", 5, line("SKU-BAR", 8));
        start(ann, "T1").andExpect(jsonPath("$.task.canRelease").value(true));
        call(post("/api/rf/picking/release"), ann)
                .andExpect(jsonPath("$.step").value("SCAN_CONTAINER"))
                .andExpect(jsonPath("$.waitingTasks").value(1));
        assertThat(container("T1").getStatus()).isEqualTo(ContainerStatus.AVAILABLE);

        JsonNode state = body(start(ann, "T1"));
        confirm(ann, state, "111006", 1, false).andExpect(jsonPath("$.task.canRelease").value(false));
        call(post("/api/rf/picking/release"), ann).andExpect(status().isConflict());
    }

    @Test
    void givingBackANewCartonRemovesIt() throws Exception {
        order("SO-1", 5, line("SKU-BAR", 1));
        call(json(post("/api/rf/picking/start"), Map.of("lpnId", "OLPN-1", "containerTypeId", cartonType.getId())), ann);
        call(post("/api/rf/picking/release"), ann).andExpect(status().isOk());
        assertThat(containers.findByLpnId("OLPN-1")).isEmpty();
    }

    // ---------- Picking ----------

    @Test
    void picksEveryDetailInUnitsThenDropsAtAPackStation() throws Exception {
        String orderId = order("SO-1", 5, line("SKU-BAR", 27), line("SKU-DRILL", 1));
        JsonNode state = body(start(ann, "T1")
                .andExpect(jsonPath("$.task.detailCount").value(2))
                .andExpect(jsonPath("$.pick.remaining").value(27))
                .andExpect(jsonPath("$.pick.directed[0].uom").value("CS"))
                .andExpect(jsonPath("$.pick.directed[0].quantity").value(4))
                .andExpect(jsonPath("$.pick.directed[1].uom").value("EA"))
                .andExpect(jsonPath("$.pick.directed[1].quantity").value(3)));

        // Four cases, then the loose three.
        state = body(confirm(ann, state, "111006", 4, false)
                .andExpect(jsonPath("$.task.closedDetails").value(0))
                .andExpect(jsonPath("$.pick.pickedQuantity").value(24))
                .andExpect(jsonPath("$.pick.directed", hasSize(1))));
        state = body(confirm(ann, state, "sku-bar", 3, false)
                .andExpect(jsonPath("$.task.closedDetails").value(1))
                .andExpect(jsonPath("$.pick.locationId").value("DC1-RS-01-01-A-01"))
                .andExpect(jsonPath("$.pick.fromLpnId").value("BOX-1"))
                .andExpect(jsonPath("$.pick.serialNumber").value("D-1")));
        call(get("/api/orders/" + orderId), ann)
                .andExpect(jsonPath("$.status").value("PICKING"))
                .andExpect(jsonPath("$.lines[0].status").value("PICKED"))
                .andExpect(jsonPath("$.lines[0].pickedQuantity").value(27));

        confirm(ann, state, "222000", 1, false)
                .andExpect(jsonPath("$.step").value("DROP"))
                .andExpect(jsonPath("$.drop.suggestedLocationId").value("DC1-PACK-01"));
        drop(ann, "DC1-BK-01-01-A-01").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.locationId", containsString("not a pack station")));
        drop(ann, "NOWHERE").andExpect(status().isBadRequest());
        drop(ann, "dc1-pack-01").andExpect(jsonPath("$.step").value("SCAN_CONTAINER"));

        // The stock is in the tote at the pack station, still reserved for the order.
        sync();
        assertThat(inventory.sumQuantityByItem(bars.getId())).isEqualTo(40);
        Container tote = container("T1");
        assertThat(tote.getCurrentLocation().getLocationId()).isEqualTo("DC1-PACK-01");
        List<InventoryRecord> inTote = inventory.findByContainer_IdIn(List.of(tote.getId()));
        assertThat(inTote).extracting(InventoryRecord::getQuantity).containsExactlyInAnyOrder(27, 1);
        assertThat(inventory.findByLocation_IdIn(List.of(shelf.getId()))).extracting(InventoryRecord::getQuantity).containsExactly(13);
        // The emptied carton and its pallet are out of service.
        assertThat(container("BOX-1").getStatus()).isEqualTo(ContainerStatus.RETIRED);
        assertThat(container("P1").getCurrentLocation()).isNull();

        call(get("/api/orders/" + orderId), ann)
                .andExpect(jsonPath("$.status").value("PICKED"))
                .andExpect(jsonPath("$.allocations", hasSize(2)))
                .andExpect(jsonPath("$.allocations[*].container.lpnId", everyItem(is("T1"))))
                .andExpect(jsonPath("$.allocations[0].quantity").value(27))
                .andExpect(jsonPath("$.allocations[0].location.locationId").value("DC1-PACK-01"))
                .andExpect(jsonPath("$.allocations[0].task.status").value("PICKED"))
                .andExpect(jsonPath("$.tasks[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.tasks[0].assignedTo").value("Ann Lee"))
                .andExpect(jsonPath("$.tasks[0].toContainer.lpnId").value("T1"))
                .andExpect(jsonPath("$.tasks[0].dropLocation.locationId").value("DC1-PACK-01"))
                .andExpect(jsonPath("$.tasks[0].pickedQuantity").value(28))
                .andExpect(jsonPath("$.tasks[0].details[0].pickedBy").value("Ann Lee"))
                .andExpect(jsonPath("$.events[?(@.type == 'PICKED')].message", hasItem(containsString("Ann Lee picked 27 EA of SKU-BAR"))))
                .andExpect(jsonPath("$.events[?(@.type == 'DROPPED')].message", hasItem(containsString("T1 dropped at pack station DC1-PACK-01"))));
        call(get("/api/allocations").param("q", "t1"), ann).andExpect(jsonPath("$.totalElements").value(2));
        call(get("/api/pick-tasks").param("q", "t1"), ann).andExpect(jsonPath("$.totalElements").value(1));
        call(get("/api/pick-tasks").param("status", "COMPLETED"), ann).andExpect(jsonPath("$.totalElements").value(1));
        // The next drop is sent to the emptier station.
        order("SO-2", 5, line("SKU-BAR", 1));
        JsonNode next = body(start(ann, "T2"));
        confirm(ann, next, "111000", 1, false).andExpect(jsonPath("$.drop.suggestedLocationId").value("DC1-PACK-02"));
    }

    @Test
    void wrongScansMoveNothing() throws Exception {
        order("SO-1", 5, line("SKU-DRILL", 1));
        JsonNode state = body(start(ann, "T1"));
        send(ann, with(state, "222000", 1, false, "locationId", "DC1-BK-01-01-A-01")).andExpect(jsonPath("$.errors.locationId").exists());
        send(ann, with(state, "222000", 1, false, "fromLpnId", "P1")).andExpect(jsonPath("$.errors.fromLpnId").exists());
        send(ann, with(state, "111000", 1, false, "serialNumber", "D-1")).andExpect(jsonPath("$.errors.itemBarcode").exists());
        send(ann, with(state, "222000", 1, false, "serialNumber", "D-2")).andExpect(jsonPath("$.errors.serialNumber").exists());
        send(ann, with(state, "222000", 2, false, "serialNumber", "D-1")).andExpect(jsonPath("$.errors.quantity").exists());
        send(ann, with(state, "222000", 0, false, "serialNumber", "D-1")).andExpect(jsonPath("$.errors.quantity").exists());
        call(get("/api/rf/picking"), ann).andExpect(jsonPath("$.pick.pickedQuantity").value(0));
        confirm(ann, state, "222000", 1, false).andExpect(jsonPath("$.step").value("DROP"));
    }

    @Test
    void casesCantExceedWhatIsLeft() throws Exception {
        order("SO-1", 5, line("SKU-BAR", 8));
        JsonNode state = body(start(ann, "T1"));
        confirm(ann, state, "111006", 2, false).andExpect(jsonPath("$.errors.quantity", containsString("only 8 EA")));
        // Picking the case broken into eaches is fine.
        confirm(ann, state, "111000", 8, false).andExpect(jsonPath("$.step").value("DROP"));
    }

    @Test
    void aRepeatedConfirmIsNotPickedTwice() throws Exception {
        String orderId = order("SO-1", 5, line("SKU-BAR", 12));
        JsonNode state = body(start(ann, "T1"));
        confirm(ann, state, "111006", 1, false).andExpect(jsonPath("$.pick.remaining").value(6));
        confirm(ann, state, "111006", 1, false).andExpect(status().isConflict());
        call(get("/api/orders/" + orderId), ann).andExpect(jsonPath("$.lines[0].pickedQuantity").value(6));
    }

    @Test
    void signingInAgainCarriesOnWithTheSamePick() throws Exception {
        order("SO-1", 5, line("SKU-BAR", 12));
        JsonNode state = body(start(ann, "T1"));
        confirm(ann, state, "111006", 1, false);
        mvc.perform(delete("/api/rf/session").cookie(ann));
        call(get("/api/rf/picking"), signIn("ann"))
                .andExpect(jsonPath("$.step").value("PICK"))
                .andExpect(jsonPath("$.pick.remaining").value(6));
    }

    @Test
    void aPickerCantConfirmSomeoneElsesPick() throws Exception {
        order("SO-1", 5, line("SKU-BAR", 1));
        JsonNode state = body(start(ann, "T1"));
        confirm(bob, state, "111000", 1, false).andExpect(status().isConflict());
    }

    @Test
    void anotherPickerCanTakeOverAHalfPickedTask() throws Exception {
        order("SO-1", 5, line("SKU-BAR", 12));
        JsonNode state = body(start(ann, "T1"));
        confirm(ann, state, "111006", 1, false);
        call(json(post("/api/rf/picking/start"), Map.of("lpnId", "t1", "takeOver", true)), bob)
                .andExpect(jsonPath("$.step").value("PICK"))
                .andExpect(jsonPath("$.task.toLpnId").value("T1"))
                .andExpect(jsonPath("$.pick.remaining").value(6));
        // It is Bob's now, and only Bob's.
        call(get("/api/rf/picking"), ann).andExpect(jsonPath("$.step").value("SCAN_CONTAINER"));
        JsonNode now = body(call(get("/api/rf/picking"), bob));
        confirm(ann, now, "111006", 1, false).andExpect(status().isConflict());
        confirm(bob, now, "111006", 1, false).andExpect(jsonPath("$.step").value("DROP"));
        // Even a task that only needs dropping can be taken over, but not while picking another.
        order("SO-2", 5, line("SKU-BAR", 1));
        start(ann, "T2");
        call(json(post("/api/rf/picking/start"), Map.of("lpnId", "T1", "takeOver", true)), ann)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.detail", containsString("already picking")));
        drop(bob, "DC1-PACK-01").andExpect(status().isOk());
        start(bob, "T1").andExpect(jsonPath("$.errors.lpnId").value("T1 is already in use"));
    }

    @Test
    void stockThatExpiresAfterAllocationCantBePicked() throws Exception {
        order("SO-1", 5, line("SKU-BAR", 1));
        JsonNode state = body(start(ann, "T1"));
        inventory.findByLocation_IdIn(List.of(shelf.getId())).getFirst().setExpiryDate(LocalDate.now().minusDays(1));
        confirm(ann, state, "111000", 1, false).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("has expired")));
        confirm(ann, state, null, 0, true).andExpect(jsonPath("$.step").value("SCAN_CONTAINER"));
    }

    @Test
    void aShortPickHoldsWhatIsMissingAndTheLineNeedsItAgain() throws Exception {
        String orderId = order("SO-1", 5, line("SKU-BAR", 10));
        JsonNode state = body(start(ann, "T1"));
        confirm(ann, state, "111000", 4, true).andExpect(jsonPath("$.step").value("DROP"));
        sync();
        assertThat(inventory.findByLocation_IdIn(List.of(shelf.getId())))
                .extracting(r -> r.getStatus() + " " + r.getQuantity())
                .containsExactlyInAnyOrder("AVAILABLE 30", "ON_HOLD 6");
        call(get("/api/orders/" + orderId), ann)
                .andExpect(jsonPath("$.status").value("PICKING"))
                .andExpect(jsonPath("$.lines[0].allocatedQuantity").value(4))
                .andExpect(jsonPath("$.lines[0].pickedQuantity").value(4))
                .andExpect(jsonPath("$.tasks[0].details[0].status").value("SHORT"))
                .andExpect(jsonPath("$.events[?(@.type == 'SHORT')].message", hasItem(containsString("6 EA of SKU-BAR missing"))));
        // It can be allocated again, from stock that isn't on hold.
        call(post("/api/orders/" + orderId + "/allocate"), ann)
                .andExpect(jsonPath("$.lines[0].allocatedQuantity").value(10))
                .andExpect(jsonPath("$.tasks", hasSize(2)));
    }

    @Test
    void aTaskWithNothingToPickNeedsNoDrop() throws Exception {
        String orderId = order("SO-1", 5, line("SKU-DRILL", 1));
        JsonNode state = body(start(ann, "T1"));
        send(ann, with(state, null, 0, true, "fromLpnId", null)).andExpect(jsonPath("$.step").value("SCAN_CONTAINER"));
        assertThat(container("T1").getStatus()).isEqualTo(ContainerStatus.AVAILABLE);
        call(get("/api/orders/" + orderId), ann)
                .andExpect(jsonPath("$.status").value("BACKORDERED"))
                .andExpect(jsonPath("$.tasks[0].status").value("COMPLETED"));
    }

    @Test
    void stockPutOnHoldAfterAllocationCantBePicked() throws Exception {
        order("SO-1", 5, line("SKU-BAR", 1));
        JsonNode state = body(start(ann, "T1"));
        inventory.findByLocation_IdIn(List.of(shelf.getId())).getFirst().setStatus(InventoryStatus.ON_HOLD);
        confirm(ann, state, "111000", 1, false).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("on hold")));
        confirm(ann, state, null, 0, true).andExpect(jsonPath("$.step").value("SCAN_CONTAINER"));
    }

    @Test
    void batchTasksPickSeveralOrdersIntoOneContainer() throws Exception {
        UUID a = received("SO-A", line("SKU-BAR", 2));
        UUID b = received("SO-B", line("SKU-BAR", 3));
        orderService.allocateBatch(List.of(a, b));
        JsonNode state = body(start(ann, "T1").andExpect(jsonPath("$.task.orderCount").value(2)));
        state = body(confirm(ann, state, "111000", 2, false).andExpect(jsonPath("$.pick.orderNumber").value("SO-B")));

        // SO-A is partly in the tote, so only SO-B can be cancelled; that leaves the task ready to drop.
        call(post("/api/orders/" + a + "/cancel"), ann).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("already been picked")));
        call(post("/api/orders/" + b + "/cancel"), ann).andExpect(jsonPath("$.status").value("CANCELLED"));
        call(get("/api/rf/picking"), ann).andExpect(jsonPath("$.step").value("DROP"));
        confirm(ann, state, "111000", 3, false).andExpect(status().isConflict());
        drop(ann, "DC1-PACK-02").andExpect(jsonPath("$.step").value("SCAN_CONTAINER"));
    }

    @Test
    void cancellingTheOrderFreesThePickersContainer() throws Exception {
        String orderId = order("SO-1", 5, line("SKU-BAR", 1));
        start(ann, "T1");
        call(post("/api/orders/" + orderId + "/cancel"), ann).andExpect(jsonPath("$.tasks[0].status").value("CANCELLED"));
        call(get("/api/rf/picking"), ann).andExpect(jsonPath("$.step").value("SCAN_CONTAINER"));
        assertThat(container("T1").getStatus()).isEqualTo(ContainerStatus.AVAILABLE);
    }

    @Test
    void pickedStockIsNotAllocatedToOtherOrders() throws Exception {
        order("SO-1", 5, line("SKU-BAR", 40));
        JsonNode state = body(start(ann, "T1"));
        state = body(confirm(ann, state, "111006", 6, false));
        confirm(ann, state, "111000", 4, false);
        drop(ann, "DC1-PACK-01");
        String other = order("SO-2", 5, line("SKU-BAR", 1));
        call(get("/api/orders/" + other), ann).andExpect(jsonPath("$.status").value("BACKORDERED"));
    }

    // ---------- Helpers ----------

    private ResultActions signInRequest(String user, String pin) throws Exception {
        sync();
        return mvc.perform(json(post("/api/rf/session"), Map.of("userCode", user, "pin", pin)));
    }

    private Cookie signIn(String user) throws Exception {
        return signInRequest(user, PIN).andExpect(status().isOk()).andReturn().getResponse().getCookie("wms_device");
    }

    private ResultActions call(MockHttpServletRequestBuilder request, Cookie user) throws Exception {
        sync();
        return mvc.perform(request.cookie(user));
    }

    private ResultActions start(Cookie user, String lpn) throws Exception {
        return call(json(post("/api/rf/picking/start"), Map.of("lpnId", lpn)), user);
    }

    private ResultActions drop(Cookie user, String location) throws Exception {
        return call(json(post("/api/rf/picking/drop"), Map.of("locationId", location)), user);
    }

    /** Confirms the shown pick, scanning everything else as directed. */
    private ResultActions confirm(Cookie user, JsonNode state, String barcode, int quantity, boolean shortPick) throws Exception {
        return send(user, scans(state, barcode, quantity, shortPick));
    }

    private ResultActions send(Cookie user, Map<String, Object> body) throws Exception {
        return call(json(post("/api/rf/picking/confirm"), body), user);
    }

    private static Map<String, Object> with(JsonNode state, String barcode, int quantity, boolean shortPick, String field, Object value) {
        Map<String, Object> body = scans(state, barcode, quantity, shortPick);
        body.put(field, value);
        return body;
    }

    private static Map<String, Object> scans(JsonNode state, String barcode, int quantity, boolean shortPick) {
        JsonNode pick = state.get("pick");
        Map<String, Object> body = new HashMap<>();
        body.put("detailId", pick.get("detailId").asString());
        body.put("pickedSoFar", pick.get("pickedQuantity").asInt());
        body.put("locationId", pick.get("locationId").asString());
        body.put("fromLpnId", pick.get("fromLpnId").isNull() ? null : pick.get("fromLpnId").asString());
        body.put("serialNumber", pick.get("serialNumber").isNull() ? null : pick.get("serialNumber").asString());
        body.put("itemBarcode", barcode);
        body.put("quantity", quantity);
        body.put("shortPick", shortPick);
        return body;
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

    private String order(String number, int priority, OrderRequest.Line... lines) {
        return orderService.create(new OrderRequest(number, "Acme", null, priority, List.of(lines))).id().toString();
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

    private Location location(String code, LocationType type, String zone, String aisle, String bay, String level, String position) {
        Location l = new Location(UUID.randomUUID());
        l.setLocationId(code);
        l.setType(type);
        l.setArea("DC1");
        l.setZone(zone);
        l.setAisle(aisle);
        l.setBay(bay);
        l.setLevel(level);
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

    private static Container lpn(String code, ContainerType type, ContainerStatus status, boolean permanent) {
        Container c = new Container(UUID.randomUUID());
        c.setLpnId(code);
        c.setContainerType(type);
        c.setStatus(status);
        c.setPermanent(permanent);
        return c;
    }

    private void stock(Item item, int quantity, String serial, Location location, Container container) {
        InventoryRecord r = new InventoryRecord(UUID.randomUUID(), item, quantity);
        r.setSerialNumber(serial);
        r.setReceivedAt(Instant.parse("2026-11-01T10:00:00Z"));
        if (location != null) {
            r.placeIn(location);
        } else {
            r.placeIn(container);
        }
        inventory.save(r);
    }
}
