package com.wms.control;

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
import com.wms.inventory.StockLedger;
import com.wms.inventory.StockTransaction;
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
import com.wms.users.DesktopUser;
import com.wms.users.Permission;
import com.wms.users.TestUsers;

import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class InventoryControlTest {

    private static final String PIN = "8264";
    private static final String DAMAGED = "00000000-0000-4000-8001-000000000001";
    private static final String RECALL = "00000000-0000-4000-8001-000000000007";
    private static final String CHECKED = "00000000-0000-4000-8001-000000000008";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private EntityManager em;
    @Autowired private ItemRepository items;
    @Autowired private LocationRepository locations;
    @Autowired private InventoryRepository inventory;
    @Autowired private ContainerRepository containers;
    @Autowired private ContainerTypeRepository types;
    @Autowired private StockLedger ledger;
    @Autowired private TestUsers people;

    private final DesktopUser second = new DesktopUser(UUID.randomUUID(), "TWO", "Second Person",
            java.util.EnumSet.allOf(Permission.class), false);
    private Item cups;
    private Item nuts;
    private Location a;
    private Location b;
    private Cookie ann;
    private Cookie bob;

    /** 100 cups loose at A, 40 nuts of lot L1 on pallet P1 at B; nothing at C and D. */
    @BeforeEach
    void fixtures() throws Exception {
        a = location("DC1-BK-01", LocationType.STORAGE, "BK", "01");
        b = location("DC1-BK-02", LocationType.STORAGE, "BK", "02");
        location("DC1-BK-03", LocationType.STORAGE, "BK", "03");
        location("DC1-RS-04", LocationType.STORAGE, "RS", "04").setMaxUnits(10);
        location("DC1-RCV-01", LocationType.RECEIVING, "RCV", "01");
        location("DC1-PACK-01", LocationType.PACKING, "PACK", "01");
        cups = item("SKU-CUP", "333000");
        nuts = item("SKU-NUT", "444000");
        nuts.setLotControlled(true);
        nuts.setExpiryControlled(true);
        item("SKU-DRILL", "222000").setSerialControlled(true);
        ContainerType pallet = type("PLT", ContainerCategory.PALLET, false);
        Container p1 = tote("P1", pallet);
        p1.setPermanent(false);
        p1.setStatus(ContainerStatus.IN_USE);
        p1.setCurrentLocation(b);
        p1 = containers.save(p1);
        containers.save(tote("T1", type("TOTE", ContainerCategory.TOTE, true)));
        stock(cups, 100, null, null, r -> r.placeIn(a));
        Container at = p1;
        stock(nuts, 40, "L1", LocalDate.now().plusDays(90), r -> r.placeIn(at));
        people.operator("ANN", "Ann Lee", PIN);
        people.operator("BOB", "Bob Ray", PIN);
        ann = signIn("ANN");
        bob = signIn("BOB");
    }

    // ---------- Looking ----------

    @Test
    void stockIsShownByItemAndLot() throws Exception {
        order("SO-1", "SKU-CUP", 30).andExpect(jsonPath("$.status").value("ALLOCATED"));
        call(get("/api/inventory/on-hand"), null).andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].item.itemId").value("SKU-CUP")).andExpect(jsonPath("$.content[0].total.total").value(100))
                .andExpect(jsonPath("$.content[0].total.available").value(70)).andExpect(jsonPath("$.content[0].total.reserved").value(30))
                .andExpect(jsonPath("$.content[0].lots", hasSize(0)))
                .andExpect(jsonPath("$.content[1].lots[0].lotNumber").value("L1")).andExpect(jsonPath("$.content[1].lots[0].total").value(40));
        call(get("/api/inventory/on-hand").param("q", "l1"), null).andExpect(jsonPath("$.totalElements").value(1));
        for (String q : List.of("sku-nut", "p1", "bk-02", "l1")) {
            call(get("/api/inventory/stock").param("q", q), null).andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.content[0].location").value("DC1-BK-02")).andExpect(jsonPath("$.content[0].lpn").value("P1"));
        }
        call(get("/api/inventory/stock"), null).andExpect(jsonPath("$.totalElements").value(2));
        call(get("/api/inventory/stock").param("held", "true"), null).andExpect(jsonPath("$.totalElements").value(0));
        call(get("/api/inventory/stock").param("q", "100%"), null).andExpect(jsonPath("$.totalElements").value(0));
    }

    // ---------- Adjustments ----------

    @Test
    void smallCorrectionsAreMadeAtOnce() throws Exception {
        change(cups, 100, 90).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPLIED"))
                .andExpect(jsonPath("$.number", matchesPattern("AD\\d{6}"))).andExpect(jsonPath("$.change").value(-10));
        assertThat(quantity(cups)).isEqualTo(90);
        call(get("/api/inventory/history").param("type", "ADJUST"), null).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].quantity").value(10)).andExpect(jsonPath("$.content[0].fromLocation").value("DC1-BK-01"))
                .andExpect(jsonPath("$.content[0].toLocation").doesNotExist()).andExpect(jsonPath("$.content[0].reason").value("Damaged: fell off"))
                .andExpect(jsonPath("$.content[0].userName").value("Test Admin"));
        for (String q : List.of("sku-cup", "fell off", "test admin", "bk-01", "ad0")) {
            call(get("/api/inventory/history").param("type", "ADJUST").param("q", q), null).andExpect(jsonPath("$.totalElements").value(1));
        }
        call(get("/api/inventory/history").param("to", LocalDate.now().minusDays(1).toString()), null).andExpect(jsonPath("$.totalElements").value(0));
        call(get("/api/inventory/history").param("from", LocalDate.now().toString()).param("to", LocalDate.now().toString())
                .param("type", "ADJUST"), null).andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void largerCorrectionsNeedSomeoneElse() throws Exception {
        JsonNode asked = body(change(cups, 100, 50).andExpect(jsonPath("$.status").value("PENDING")));
        assertThat(quantity(cups)).isEqualTo(100);
        change(cups, 100, 99).andExpect(status().isConflict());
        String url = "/api/inventory/adjustments/" + asked.get("id").asString();
        call(post(url + "/approve"), null).andExpect(status().isConflict()).andExpect(jsonPath("$.detail", containsString("Someone else")));
        call(post(url + "/approve").requestAttr(DesktopUser.ATTRIBUTE, second), null).andExpect(jsonPath("$.status").value("APPLIED"))
                .andExpect(jsonPath("$.decider").value("Second Person"));
        assertThat(quantity(cups)).isEqualTo(50);
        call(post(url + "/approve").requestAttr(DesktopUser.ATTRIBUTE, second), null).andExpect(status().isConflict());
        call(get("/api/inventory/history").param("type", "ADJUST"), null)
                .andExpect(jsonPath("$.content[0].reason", containsString("approved by Second Person")))
                .andExpect(jsonPath("$.content[0].userName").value("Test Admin"));
        JsonNode other = body(change(cups, 50, 100));
        call(post("/api/inventory/adjustments/" + other.get("id").asString() + "/reject"), null).andExpect(jsonPath("$.status").value("REJECTED"));
        assertThat(quantity(cups)).isEqualTo(50);
        call(get("/api/inventory/adjustments").param("status", "REJECTED").param("q", "sku-cup"), null).andExpect(jsonPath("$.totalElements").value(1));
        call(get("/api/inventory/adjustments").param("status", "PENDING"), null).andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void theLimitCanBeChanged() throws Exception {
        call(json(put("/api/inventory/settings"), Map.of("approvalLimit", 60)), null).andExpect(jsonPath("$.approvalLimit").value(60));
        change(cups, 100, 50).andExpect(jsonPath("$.status").value("APPLIED"));
        call(json(put("/api/inventory/settings"), Map.of("approvalLimit", -1)), null).andExpect(status().isBadRequest());
        call(get("/api/inventory/settings"), null).andExpect(jsonPath("$.approvalLimit").value(60));
    }

    @Test
    void reservedStockCannotBeCorrectedAway() throws Exception {
        order("SO-1", "SKU-CUP", 95);
        change(cups, 100, 90).andExpect(status().isConflict()).andExpect(jsonPath("$.errors.newQuantity").value("at least 95"));
        change(cups, 100, 95).andExpect(status().isOk());
        change(cups, 100, 96).andExpect(status().isConflict()).andExpect(jsonPath("$.detail", containsString("There are now 95 EA")));
        change(cups, 95, 95).andExpect(status().isBadRequest());
        call(json(post("/api/inventory/adjustments/change"), Map.of("inventoryId", record(cups).getId(), "expectedQuantity", 95, "newQuantity", 96)), null)
                .andExpect(jsonPath("$.errors.reasonId").exists());
    }

    @Test
    void anLpnCorrectedToNothingIsDoneWith() throws Exception {
        call(json(put("/api/inventory/settings"), Map.of("approvalLimit", 100)), null);
        change(nuts, 40, 0).andExpect(status().isOk());
        assertThat(inventory.findByItem_Id(nuts.getId())).isEmpty();
        assertThat(container("P1").getStatus()).isEqualTo(ContainerStatus.RETIRED);
    }

    @Test
    void foundStockIsAdded() throws Exception {
        add(Map.of("itemId", "SKU-CUP", "locationId", "dc1-bk-01", "quantity", 5)).andExpect(jsonPath("$.status").value("APPLIED"));
        assertThat(quantity(cups)).isEqualTo(105);
        add(Map.of("itemId", "SKU-NUT", "lpnId", "P1", "quantity", 2, "lotNumber", "l1", "expiryDate", LocalDate.now().plusDays(90).toString()))
                .andExpect(status().isOk());
        assertThat(quantity(nuts)).isEqualTo(42);
        add(Map.of("itemId", "SKU-NUT", "locationId", "DC1-BK-03", "quantity", 2)).andExpect(jsonPath("$.errors.lotNumber").exists());
        add(Map.of("itemId", "SKU-NUT", "locationId", "DC1-BK-03", "quantity", 2, "lotNumber", "L2")).andExpect(jsonPath("$.errors.expiryDate").exists());
        add(Map.of("itemId", "SKU-DRILL", "locationId", "DC1-BK-03", "quantity", 1, "serialNumber", "D1")).andExpect(status().isOk());
        add(Map.of("itemId", "SKU-DRILL", "locationId", "DC1-BK-03", "quantity", 1, "serialNumber", "D1")).andExpect(jsonPath("$.errors.serialNumber").exists());
        add(Map.of("itemId", "SKU-CUP", "locationId", "DC1-RCV-01", "quantity", 1)).andExpect(jsonPath("$.errors.locationId").exists());
        add(Map.of("itemId", "SKU-CUP", "lpnId", "T1", "quantity", 1)).andExpect(jsonPath("$.errors.lpnId").exists());
        add(Map.of("itemId", "SKU-CUP", "locationId", "DC1-BK-03", "quantity", 1, "status", "DAMAGED")).andExpect(status().isOk());
        call(get("/api/inventory/stock").param("held", "true"), null).andExpect(jsonPath("$.content[0].holdReason").value("Damaged"));
        books();
    }

    // ---------- Holds ----------

    @Test
    void heldStockGoesOutOfUseAndOrdersGetOther() throws Exception {
        Location c = locations.findByLocationId("DC1-BK-03").orElseThrow();
        stock(cups, 10, null, null, r -> r.placeIn(c));
        JsonNode order = body201(order("SO-1", "SKU-CUP", 30));
        assertThat(order.get("status").asString()).isEqualTo("ALLOCATED");
        call(json(post("/api/inventory/holds/preview"), Map.of("locationId", "DC1-BK-01")), null).andExpect(jsonPath("$.quantity").value(100));
        hold(Map.of("locationId", "DC1-BK-01"), false).andExpect(status().isOk()).andExpect(jsonPath("$.quantity").value(100))
                .andExpect(jsonPath("$.ordersReallocated").value(1)).andExpect(jsonPath("$.stuck").value(0));
        call(get("/api/orders/" + order.get("id").asString()), null).andExpect(jsonPath("$.status").value("PARTIALLY_ALLOCATED"))
                .andExpect(jsonPath("$.allocations[0].location.locationId").value("DC1-BK-03")).andExpect(jsonPath("$.allocations[0].quantity").value(10))
                .andExpect(jsonPath("$.events[*].message", hasItem(containsString("let go of 30 EA of SKU-CUP"))));
        call(get("/api/inventory/stock").param("held", "true"), null).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].holdReason").value("Recall: batch 7"));
        hold(Map.of("locationId", "DC1-BK-01"), false).andExpect(status().isConflict());

        List<String> ids = List.of(record(cups, 100).getId().toString());
        call(json(post("/api/inventory/holds/release"), Map.of("inventoryIds", ids)), null).andExpect(jsonPath("$.errors.reasonId").exists());
        call(json(post("/api/inventory/holds/release"), Map.of("inventoryIds", ids, "reasonId", CHECKED)), null).andExpect(jsonPath("$.quantity").value(100));
        call(json(post("/api/inventory/holds/release"), Map.of("inventoryIds", ids, "reasonId", CHECKED)), null).andExpect(status().isConflict());
        call(post("/api/orders/" + order.get("id").asString() + "/allocate"), null).andExpect(jsonPath("$.status").value("ALLOCATED"));
        call(get("/api/inventory/history").param("type", "HOLD"), null).andExpect(jsonPath("$.content[0].fromStatus").value("AVAILABLE"))
                .andExpect(jsonPath("$.content[0].toStatus").value("ON_HOLD"));
        call(get("/api/inventory/history").param("type", "RELEASE"), null).andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void holdsCanBeForAnLpnALotOrAnItem() throws Exception {
        hold(Map.of("lpnId", "p1"), false).andExpect(jsonPath("$.quantity").value(40));
        hold(Map.of("itemId", "SKU-NUT", "lotNumber", "L1"), true).andExpect(jsonPath("$.quantity").value(40));
        assertThat(record(nuts).getStatus()).isEqualTo(InventoryStatus.DAMAGED);
        hold(Map.of("itemId", "SKU-CUP"), true).andExpect(jsonPath("$.quantity").value(100));
        hold(Map.of("lpnId", "NOPE"), false).andExpect(jsonPath("$.errors.lpnId").exists());
        hold(Map.of(), false).andExpect(status().isBadRequest());
    }

    @Test
    void stockBeingPickedStaysWithItsOrderButCannotBePicked() throws Exception {
        order("SO-1", "SKU-CUP", 30);
        JsonNode pick = body(call(json(post("/api/rf/picking/start"), Map.of("lpnId", "T1")), ann)).get("pick");
        hold(Map.of("itemId", "SKU-CUP"), false).andExpect(jsonPath("$.ordersReallocated").value(0)).andExpect(jsonPath("$.stuck").value(30));
        pick(pick, 30, false).andExpect(status().isConflict()).andExpect(jsonPath("$.detail", containsString("put on hold")));
        pick(pick, 0, true).andExpect(status().isOk());
        assertThat(record(cups).getQuantity()).isEqualTo(100);
    }

    // ---------- Moves ----------

    @Test
    void anLpnIsMovedWhole() throws Exception {
        call(get("/api/rf/moves/from/p1"), ann).andExpect(jsonPath("$.lpn").value("P1")).andExpect(jsonPath("$.lines", hasSize(1)));
        moveLpn("P1", "DC1-RCV-01", false).andExpect(jsonPath("$.errors.toLocationId", containsString("not a storage location")));
        moveLpn("P1", "DC1-RS-04", false).andExpect(status().isConflict()).andExpect(jsonPath("$.errors.capacity", containsString("takes at most 10 EA")));
        moveLpn("P1", "DC1-BK-03", false).andExpect(status().isOk());
        moveLpn("P1", "DC1-BK-03", false).andExpect(status().isOk());
        assertThat(container("P1").getCurrentLocation().getLocationId()).isEqualTo("DC1-BK-03");
        moveLpn("P1", "DC1-RS-04", true).andExpect(status().isOk());
        call(get("/api/inventory/history").param("type", "MOVE"), null).andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[1].fromLocation").value("DC1-BK-02")).andExpect(jsonPath("$.content[1].toLocation").value("DC1-BK-03"))
                .andExpect(jsonPath("$.content[0].userName").value("Ann Lee"));
        order("SO-1", "SKU-NUT", 5);
        moveLpn("P1", "DC1-BK-02", false).andExpect(status().isConflict()).andExpect(jsonPath("$.detail", containsString("5 EA in P1 are reserved")));
        call(get("/api/rf/moves/from/T1"), ann).andExpect(status().isBadRequest());
        call(get("/api/rf/moves/from/NOPE"), ann).andExpect(status().isBadRequest());
        call(get("/api/rf/moves/from/DC1-RCV-01"), ann).andExpect(status().isBadRequest());
    }

    @Test
    void looseStockIsMovedInPart() throws Exception {
        order("SO-1", "SKU-CUP", 30);
        call(get("/api/rf/moves/from/dc1-bk-01"), ann).andExpect(jsonPath("$.lines[0].free").value(70));
        UUID request = UUID.randomUUID();
        for (int sentTwice = 0; sentTwice < 2; sentTwice++) {
            moveLoose(request, 20, "DC1-BK-03").andExpect(status().isOk());
        }
        assertThat(record(cups, 80).getLocation().getLocationId()).isEqualTo("DC1-BK-01");
        assertThat(record(cups, 20).getLocation().getLocationId()).isEqualTo("DC1-BK-03");
        moveLoose(UUID.randomUUID(), 60, "DC1-BK-03").andExpect(jsonPath("$.errors.quantity", containsString("Only 50 EA can be moved")));
        moveLoose(UUID.randomUUID(), 10, "DC1-BK-03").andExpect(status().isOk());
        assertThat(record(cups, 30).getLocation().getLocationId()).isEqualTo("DC1-BK-03");
        moveLoose(UUID.randomUUID(), 10, "DC1-BK-01").andExpect(jsonPath("$.errors.toLocationId").exists());
        moveLoose(null, 10, "DC1-BK-03").andExpect(status().isBadRequest());
        call(get("/api/rf/moves/from/DC1-BK-02"), ann).andExpect(jsonPath("$.errors.code", containsString("nothing loose")));
        books();
    }

    // ---------- Counts ----------

    @Test
    void countsArePlanned() throws Exception {
        plan(Map.of("area", "DC1", "zone", "BK")).andExpect(jsonPath("$.created").value(2));
        plan(Map.of("area", "DC1", "zone", "BK")).andExpect(status().isConflict());
        plan(Map.of("area", "DC1", "zone", "BK", "includeEmpty", true)).andExpect(jsonPath("$.created").value(1)).andExpect(jsonPath("$.skipped").value(2));
        plan(Map.of("itemId", "SKU-CUP")).andExpect(status().isConflict());
        plan(Map.of("locationId", "DC1-RCV-01")).andExpect(status().isBadRequest());
        plan(Map.of()).andExpect(status().isBadRequest());
        call(get("/api/inventory/counts"), null).andExpect(jsonPath("$.totalElements").value(3));
        call(get("/api/inventory/counts").param("q", "bk-01").param("status", "OPEN"), null).andExpect(jsonPath("$.totalElements").value(1));
        call(get("/api/rf/counts"), ann).andExpect(jsonPath("$.waitingCount").value(3)).andExpect(jsonPath("$.waiting[0].location").value("DC1-BK-01"));
    }

    @Test
    void aRightCountChangesNothing() throws Exception {
        String id = counting("DC1-BK-01");
        enter(id, Map.of("itemBarcode", "333000", "quantity", 60)).andExpect(status().isOk());
        enter(id, Map.of("itemBarcode", "SKU-CUP", "quantity", 40)).andExpect(jsonPath("$.counting.entries", hasSize(2)));
        finish(id).andExpect(jsonPath("$.message").value("DC1-BK-01 is counted.")).andExpect(jsonPath("$.counting").doesNotExist());
        call(get("/api/inventory/counts/" + id), null).andExpect(jsonPath("$.task.status").value("COMPLETED")).andExpect(jsonPath("$.results[0].difference").value(0));
        call(get("/api/inventory/history").param("type", "COUNT"), null).andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void theDeviceNeverSeesWhatIsExpected() throws Exception {
        plan(Map.of("locationId", "DC1-BK-01"));
        String seen = call(json(post("/api/rf/counts/start"), Map.of("locationId", "DC1-BK-01")), ann).andReturn().getResponse().getContentAsString();
        assertThat(seen).doesNotContain("100").doesNotContain("SKU-CUP").doesNotContain("expected");
    }

    @Test
    void smallDifferencesArePutRight() throws Exception {
        String id = counting("DC1-BK-02");
        enter(id, Map.of("itemBarcode", "444000", "quantity", 38, "lotNumber", "L1")).andExpect(status().isOk()); // loose, not in P1
        enter(id, Map.of("itemBarcode", "333000", "quantity", 3));
        JsonNode state = body(enter(id, Map.of("itemBarcode", "333000", "quantity", 9)));
        call(delete("/api/rf/counts/" + id + "/entries/" + state.get("counting").get("entries").get(0).get("id").asString()), ann).andExpect(status().isOk());
        enter(id, Map.of("itemBarcode", "444000", "quantity", 38, "lotNumber", "L1", "lpnId", "p1")).andExpect(jsonPath("$.counting.entries", hasSize(3)));
        finish(id).andExpect(jsonPath("$.message", containsString("small differences are put right")));
        assertThat(quantity(nuts)).isEqualTo(38);
        assertThat(quantity(cups)).isEqualTo(112);
        call(get("/api/inventory/history").param("type", "COUNT"), null).andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[*].userName", everyItem(is("Ann Lee"))));
        books();
    }

    @Test
    void largeDifferencesAreReviewedBySomeoneElse() throws Exception {
        order("SO-1", "SKU-CUP", 90).andExpect(jsonPath("$.status").value("ALLOCATED"));
        String id = counting("DC1-BK-01");
        enter(id, Map.of("itemBarcode", "333000", "quantity", 20));
        finish(id).andExpect(jsonPath("$.message", containsString("supervisor will review")));
        assertThat(quantity(cups)).isEqualTo(100);
        call(json(post("/api/rf/counts/start"), Map.of("locationId", "DC1-BK-01")), bob).andExpect(status().isBadRequest());
        DesktopUser counter = new DesktopUser(people.idOf("ANN"), "ANN", "Ann Lee", java.util.EnumSet.allOf(Permission.class), false);
        call(post("/api/inventory/counts/" + id + "/approve").requestAttr(DesktopUser.ATTRIBUTE, counter), null).andExpect(status().isConflict());

        call(post("/api/inventory/counts/" + id + "/recount"), null).andExpect(jsonPath("$.task.status").value("RECOUNT"));
        call(get("/api/rf/counts"), bob).andExpect(jsonPath("$.waiting[0].again").value(true));
        call(json(post("/api/rf/counts/start"), Map.of("locationId", "DC1-BK-01")), bob).andExpect(status().isOk());
        enter(bob, id, Map.of("itemBarcode", "333000", "quantity", 20));
        call(post("/api/rf/counts/" + id + "/finish"), bob).andExpect(status().isOk());
        call(post("/api/inventory/counts/" + id + "/approve"), null).andExpect(jsonPath("$.task.status").value("COMPLETED"))
                .andExpect(jsonPath("$.task.rounds").value(2)).andExpect(jsonPath("$.results[0].difference").value(-80));
        assertThat(quantity(cups)).isEqualTo(20);
        call(get("/api/orders").param("q", "SO-1"), null).andExpect(jsonPath("$.content[0].status").value("PARTIALLY_ALLOCATED"))
                .andExpect(jsonPath("$.content[0].allocatedQuantity").value(20));
        books();
    }

    @Test
    void aLocationIsLockedWhileItIsCounted() throws Exception {
        String id = counting("DC1-BK-01");
        order("SO-1", "SKU-CUP", 5).andExpect(jsonPath("$.status").value("BACKORDERED"));
        change(cups, 100, 99).andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value("DC1-BK-01 is being counted."));
        moveLoose(UUID.randomUUID(), 5, "DC1-BK-03").andExpect(status().isConflict());
        moveLpn("P1", "DC1-BK-01", false).andExpect(jsonPath("$.errors.toLocationId", containsString("being counted")));
        call(post("/api/inventory/counts/" + id + "/cancel"), null).andExpect(jsonPath("$.task.status").value("CANCELLED"));
        call(post("/api/rf/counts/" + id + "/finish"), ann).andExpect(status().isConflict());
        change(cups, 100, 99).andExpect(status().isOk());
    }

    @Test
    void countingWaitsForPicksAndPicksWaitForCounting() throws Exception {
        order("SO-1", "SKU-CUP", 5);
        plan(Map.of("locationId", "DC1-BK-01"));
        JsonNode pick = body(call(json(post("/api/rf/picking/start"), Map.of("lpnId", "T1")), ann)).get("pick");
        call(json(post("/api/rf/counts/start"), Map.of("locationId", "DC1-BK-01")), bob).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("is being picked there")));
        call(get("/api/rf/counts"), bob).andExpect(jsonPath("$.counting").exists()); // the same transaction here; a real one is rolled back
        pick(pick, 5, false).andExpect(status().isConflict()).andExpect(jsonPath("$.detail", containsString("is being counted")));
    }

    @Test
    void noPickStartsWhereSomeoneCounts() throws Exception {
        order("SO-1", "SKU-CUP", 5);
        counting("DC1-BK-01");
        call(json(post("/api/rf/picking/start"), Map.of("lpnId", "T1")), bob).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("The waiting pick tasks are at locations being counted."));
    }

    @Test
    void countingIsChecked() throws Exception {
        call(json(post("/api/rf/counts/start"), Map.of("locationId", "DC1-BK-03")), ann).andExpect(jsonPath("$.errors.locationId").value("No count is planned at DC1-BK-03"));
        String id = counting("DC1-BK-02");
        plan(Map.of("locationId", "DC1-BK-01"));
        call(json(post("/api/rf/counts/start"), Map.of("locationId", "DC1-BK-01")), ann).andExpect(status().isConflict());
        call(json(post("/api/rf/counts/start"), Map.of("locationId", "DC1-BK-02")), bob).andExpect(jsonPath("$.errors.assignedTo").value("Ann Lee"));
        enter(id, Map.of("itemBarcode", "NOPE", "quantity", 1)).andExpect(jsonPath("$.errors.itemBarcode").exists());
        enter(id, Map.of("itemBarcode", "444000", "quantity", 1)).andExpect(jsonPath("$.errors.lotNumber").exists());
        enter(id, Map.of("itemBarcode", "333000", "quantity", 0)).andExpect(jsonPath("$.errors.quantity").exists());
        enter(id, Map.of("itemBarcode", "333000", "quantity", 1, "lpnId", "T1")).andExpect(jsonPath("$.errors.lpnId", containsString("not recorded here")));
        enter(id, Map.of("itemBarcode", "222000")).andExpect(jsonPath("$.errors.serialNumbers").exists());
        enter(id, Map.of("itemBarcode", "222000", "serialNumbers", List.of("D1", "D2"))).andExpect(jsonPath("$.counting.entries", hasSize(2)));
        enter(id, Map.of("itemBarcode", "222000", "serialNumbers", List.of("D1"))).andExpect(jsonPath("$.errors.serialNumbers").value("D1 is already counted"));
        UUID request = UUID.randomUUID();
        for (int sentTwice = 0; sentTwice < 2; sentTwice++) {
            enter(id, Map.of("requestId", request, "itemBarcode", "333000", "quantity", 1)).andExpect(jsonPath("$.counting.entries", hasSize(3)));
        }
        call(get("/api/rf/counts/items/444000"), ann).andExpect(jsonPath("$.item.lotControlled").value(true));
        call(json(post("/api/rf/counts/start"), Map.of("locationId", "DC1-BK-02", "takeOver", true)), bob).andExpect(status().isOk());
        finish(id).andExpect(status().isConflict());
        call(post("/api/rf/counts/" + id + "/give-back"), bob).andExpect(jsonPath("$.counting").doesNotExist());
        call(get("/api/inventory/counts/" + id), null).andExpect(jsonPath("$.task.status").value("OPEN"));
        order("SO-1", "SKU-NUT", 5).andExpect(jsonPath("$.status").value("ALLOCATED"));
    }

    @Test
    void anUnknownLotFoundIsHeldUntilItsDateIsKnown() throws Exception {
        String id = counting("DC1-BK-03", true);
        enter(id, Map.of("itemBarcode", "444000", "quantity", 4, "lotNumber", "L9"));
        enter(id, Map.of("itemBarcode", "444000", "quantity", 3, "lotNumber", "L1"));
        finish(id).andExpect(status().isOk());
        assertThat(record(nuts, 4).getStatus()).isEqualTo(InventoryStatus.ON_HOLD);
        assertThat(record(nuts, 3).getExpiryDate()).isEqualTo(LocalDate.now().plusDays(90));
        assertThat(record(nuts, 3).getStatus()).isEqualTo(InventoryStatus.AVAILABLE);
    }

    // ---------- Reasons ----------

    @Test
    void reasonsAreKept() throws Exception {
        call(get("/api/inventory/reasons"), null).andExpect(jsonPath("$", hasSize(8)));
        Map<String, Object> reason = new HashMap<>(Map.of("kind", "HOLD", "code", "odd", "name", "Odd smell", "status", "ACTIVE"));
        JsonNode created = mapper.readTree(call(json(post("/api/inventory/reasons"), reason), null).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        call(json(post("/api/inventory/reasons"), reason), null).andExpect(jsonPath("$.errors.code").exists());
        String url = "/api/inventory/reasons/" + created.get("id").asString();
        reason.put("status", "INACTIVE");
        call(json(put(url), reason), null).andExpect(status().isBadRequest());
        reason.put("version", created.get("version").asLong());
        call(json(put(url), reason), null).andExpect(status().isOk());
        call(json(post("/api/inventory/holds"), Map.of("scope", Map.of("lpnId", "P1"), "reasonId", created.get("id").asString())), null)
                .andExpect(jsonPath("$.errors.reasonId").exists());
        call(delete(url), null).andExpect(status().isNoContent());
    }

    // ---------- Helpers ----------

    private void stock(Item item, int quantity, String lot, LocalDate expiry, java.util.function.Consumer<InventoryRecord> place) {
        InventoryRecord r = new InventoryRecord(UUID.randomUUID(), item, quantity);
        r.setLotNumber(lot);
        r.setExpiryDate(expiry);
        place.accept(r);
        r = inventory.save(r);
        ledger.log(StockTransaction.Type.OPENING, r, quantity, StockLedger.Place.OUTSIDE, StockLedger.Place.of(r), StockLedger.Note.by(null, null));
    }

    /** The history adds up to the stock. */
    private void books() {
        sync();
        for (Item item : List.of(cups, nuts)) {
            Number in = (Number) em.createQuery("select coalesce(sum(t.quantity), 0) from StockTransaction t where t.itemId = :i and t.fromLocation is null and t.fromLpn is null")
                    .setParameter("i", item.getId()).getSingleResult();
            Number out = (Number) em.createQuery("select coalesce(sum(t.quantity), 0) from StockTransaction t where t.itemId = :i and t.toLocation is null and t.toLpn is null")
                    .setParameter("i", item.getId()).getSingleResult();
            assertThat(in.longValue() - out.longValue()).isEqualTo(quantity(item));
        }
    }

    private int quantity(Item item) {
        sync();
        return inventory.findByItem_Id(item.getId()).stream().mapToInt(InventoryRecord::getQuantity).sum();
    }

    private InventoryRecord record(Item item) {
        sync();
        return inventory.findByItem_Id(item.getId()).getFirst();
    }

    private InventoryRecord record(Item item, int quantity) {
        sync();
        return inventory.findByItem_Id(item.getId()).stream().filter(r -> r.getQuantity() == quantity).findFirst().orElseThrow();
    }

    private ResultActions change(Item item, int expected, int quantity) throws Exception {
        return call(json(post("/api/inventory/adjustments/change"), Map.of("inventoryId", record(item).getId(), "expectedQuantity", expected,
                "newQuantity", quantity, "reasonId", DAMAGED, "note", "fell off")), null);
    }

    private ResultActions add(Map<String, Object> what) throws Exception {
        Map<String, Object> body = new HashMap<>(what);
        body.put("reasonId", DAMAGED);
        return call(json(post("/api/inventory/adjustments/add"), body), null);
    }

    private ResultActions hold(Map<String, Object> scope, boolean damaged) throws Exception {
        return call(json(post("/api/inventory/holds"), Map.of("scope", scope, "damaged", damaged, "reasonId", RECALL, "note", "batch 7")), null);
    }

    private ResultActions moveLpn(String lpn, String to, boolean over) throws Exception {
        return call(json(post("/api/rf/moves/lpn"), Map.of("lpnId", lpn, "toLocationId", to, "allowOver", over)), ann);
    }

    private ResultActions moveLoose(UUID request, int quantity, String to) throws Exception {
        sync();
        Map<String, Object> body = new HashMap<>(Map.of("inventoryId", inventory.findByLocation_IdIn(List.of(a.getId())).getFirst().getId(),
                "quantity", quantity, "toLocationId", to));
        body.put("requestId", request);
        return call(json(post("/api/rf/moves/loose"), body), ann);
    }

    private ResultActions plan(Map<String, Object> plan) throws Exception {
        return call(json(post("/api/inventory/counts"), plan), null);
    }

    private String counting(String location) throws Exception {
        return counting(location, false);
    }

    private String counting(String location, boolean empty) throws Exception {
        plan(Map.of("locationId", location, "includeEmpty", empty)).andExpect(status().isOk());
        return body(call(json(post("/api/rf/counts/start"), Map.of("locationId", location)), ann)).get("counting").get("id").asString();
    }

    private ResultActions enter(String id, Map<String, Object> entry) throws Exception {
        return enter(ann, id, entry);
    }

    private ResultActions enter(Cookie who, String id, Map<String, Object> entry) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("requestId", UUID.randomUUID()));
        body.putAll(entry);
        return call(json(post("/api/rf/counts/" + id + "/entries"), body), who);
    }

    private ResultActions finish(String id) throws Exception {
        return call(post("/api/rf/counts/" + id + "/finish"), ann);
    }

    private ResultActions pick(JsonNode pick, int quantity, boolean shortPick) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("detailId", pick.get("detailId").asString(), "pickedSoFar", 0, "locationId", "DC1-BK-01",
                "itemBarcode", "333000", "quantity", quantity, "shortPick", shortPick));
        return call(json(post("/api/rf/picking/confirm"), body), ann);
    }

    private ResultActions order(String number, String item, int quantity) throws Exception {
        return call(json(post("/api/orders"), Map.of("orderNumber", number, "customerName", "Cafe", "lines", List.of(Map.of("itemId", item, "quantity", quantity)))), null);
    }

    private JsonNode body201(ResultActions result) throws Exception {
        return mapper.readTree(result.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private Location location(String code, LocationType type, String zone, String position) {
        Location l = new Location(UUID.randomUUID());
        l.setLocationId(code);
        l.setType(type);
        l.setArea("DC1");
        l.setZone(zone);
        if (type == LocationType.STORAGE) {
            l.setAisle("01");
            l.setBay("01");
            l.setLevel("A");
        }
        l.setPosition(position);
        l.setWidth(BigDecimal.TEN);
        l.setDepth(BigDecimal.TEN);
        l.setHeight(BigDecimal.TEN);
        l.setDimensionUom(DimensionUom.IN);
        return locations.save(l);
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

    private Item item(String code, String barcode) {
        Item i = new Item(UUID.randomUUID());
        i.setItemId(code);
        i.setDescription(code);
        i.setStatus(ActiveStatus.ACTIVE);
        i.setBarcode(barcode);
        return items.save(i);
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
}
