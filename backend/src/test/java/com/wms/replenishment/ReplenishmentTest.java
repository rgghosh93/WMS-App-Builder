package com.wms.replenishment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

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
import com.wms.users.TestUsers;

import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ReplenishmentTest {

    private static final String PIN = "8264";
    private static final String DAMAGED = "00000000-0000-4000-8001-000000000001";
    private static final String RECALL = "00000000-0000-4000-8001-000000000007";

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

    private Item cups;
    private Cookie ann;
    private Cookie bob;

    /** Cups (case of 6): 5 at PICK-1, 40 on pallet P1 at RS-1 (older), 30 loose at RS-2. Nothing at PICK-2. */
    @BeforeEach
    void fixtures() throws Exception {
        cups = item("SKU-CUP", "333000");
        UnitOfMeasure cs = new UnitOfMeasure(UUID.randomUUID());
        cs.setUomCode("CS");
        cs.setDescription("Case");
        cs.setKind(UomKind.CASE);
        cs.setStatus(ActiveStatus.ACTIVE);
        ItemUom pack = new ItemUom(UUID.randomUUID(), cups, uoms.save(cs));
        pack.setQuantity(6);
        pack.setBarcode("333006");
        itemUoms.save(pack);
        Location pick = location("PICK-1", LocationType.STORAGE);
        location("PICK-2", LocationType.STORAGE);
        Location one = location("RS-1", LocationType.STORAGE);
        Location two = location("RS-2", LocationType.STORAGE);
        location("DC1-PACK-01", LocationType.PACKING);
        stock(cups, 5, 1, r -> r.placeIn(pick));
        Container p1 = lpn("P1", type("PLT", ContainerCategory.PALLET, false), ContainerStatus.IN_USE, false);
        p1.setCurrentLocation(one);
        Container pallet = containers.save(p1);
        stock(cups, 40, 2, r -> r.placeIn(pallet));
        stock(cups, 30, 3, r -> r.placeIn(two));
        containers.save(lpn("T1", type("TOTE", ContainerCategory.TOTE, true), ContainerStatus.AVAILABLE, true));
        people.operator("ANN", "Ann Lee", PIN);
        people.operator("BOB", "Bob Ray", PIN);
        ann = signIn("ANN");
        bob = signIn("BOB");
    }

    // ---------- Rules ----------

    @Test
    void rulesAreCheckedAndShowHowTheLocationStands() throws Exception {
        String id = rule("PICK-1", 10, 50, Map.of());
        call(get("/api/replenishment/rules")).andExpect(jsonPath("$", hasSize(1))).andExpect(jsonPath("$[0].onHand").value(5))
                .andExpect(jsonPath("$[0].needed").value(45)).andExpect(jsonPath("$[0].multiple").value(1));
        save(null, "PICK-1", 1, 2, Map.of()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.locationId", containsString("already")));
        save(null, "PICK-2", 5, 5, Map.of()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.maxQuantity").exists());
        save(null, "PICK-2", 5, 10, Map.of("multiple", 6)).andExpect(jsonPath("$.errors.multiple").exists());
        save(null, "DC1-PACK-01", 5, 10, Map.of()).andExpect(jsonPath("$.errors.locationId", containsString("not a storage")));
        save(null, "PICK-2", 5, 10, Map.of("sourceZoneId", UUID.randomUUID())).andExpect(jsonPath("$.errors.sourceZoneId").exists());
        save(null, "PICK-2", -1, 10, Map.of()).andExpect(status().isBadRequest());
        Item drill = item("SKU-DRILL", "222000");
        drill.setSerialControlled(true);
        save(null, "PICK-2", 1, 2, Map.of("itemId", drill.getId())).andExpect(jsonPath("$.errors.itemId", containsString("serial")));
        save(id, "PICK-1", 10, 50, Map.of("version", 7)).andExpect(status().isConflict());
        save(id, "PICK-1", 3, 50, Map.of("version", 0, "status", "INACTIVE")).andExpect(status().isOk()).andExpect(jsonPath("$.onHand").doesNotExist());
        assertThat(run().get("quantity").asInt()).isZero();
        // What a rule names can't be deleted.
        call(delete("/api/locations/" + id("PICK-1"))).andExpect(status().isConflict());
        call(delete("/api/items/" + cups.getId())).andExpect(status().isConflict());
        call(delete("/api/replenishment/rules/" + id)).andExpect(status().isNoContent());
        call(get("/api/replenishment/rules/" + id)).andExpect(status().isNotFound());
    }

    // ---------- Planning ----------

    @Test
    void aLocationBelowItsMinimumIsFilledToItsMaximum() throws Exception {
        rule("PICK-1", 10, 50, Map.of());
        JsonNode result = run();
        assertThat(result.get("quantity").asInt()).isEqualTo(45);
        assertThat(result.get("still").asInt()).isZero();
        // The oldest first, then the rest of what is needed.
        assertThat(plans()).containsExactly("40 RS-1/P1 BELOW_MINIMUM", "5 RS-2/null BELOW_MINIMUM");
        assertThat(run().get("quantity").asInt()).as("nothing more while that is coming").isZero();
        call(get("/api/replenishment/rules")).andExpect(jsonPath("$[0].coming").value(45)).andExpect(jsonPath("$[0].needed").value(0));
        call(get("/api/replenishment/tasks").param("q", "rs-2")).andExpect(jsonPath("$.totalElements").value(1));
        call(get("/api/replenishment/tasks").param("status", "COMPLETED")).andExpect(jsonPath("$.totalElements").value(0));
        // Set aside: it shows as reserved.
        call(get("/api/inventory/on-hand").param("q", "SKU-CUP")).andExpect(jsonPath("$.content[0].total.reserved").value(45))
                .andExpect(jsonPath("$.content[0].total.available").value(30));
    }

    @Test
    void noMoreIsBroughtThanTheLocationCanBear() throws Exception {
        Item heavy = items.findById(cups.getId()).orElseThrow();
        heavy.setWeight(BigDecimal.TEN);
        heavy.setWeightUom(WeightUom.LB);
        Location pick = locations.findByLocationId("PICK-1").orElseThrow();
        pick.setMaxWeight(BigDecimal.valueOf(255)); // 25 of them, and 5 are there
        pick.setMaxWeightUom(WeightUom.LB);
        rule("PICK-1", 30, 50, Map.of());
        assertThat(run().get("quantity").asInt()).isEqualTo(20);
        assertThat(run().get("quantity").asInt()).isZero();
    }

    @Test
    void anEmptyLocationIsMoreUrgentAndMultiplesAreKept() throws Exception {
        rule("PICK-1", 10, 20, Map.of("multiple", 6));
        rule("PICK-2", 0, 13, Map.of("multiple", 6));
        assertThat(run().get("quantity").asInt()).as("nothing below a minimum of none").isEqualTo(12);
        assertThat(plans()).containsExactly("12 RS-1/P1 BELOW_MINIMUM");
        call(delete("/api/replenishment/rules/" + ruleId("PICK-2"))).andExpect(status().isNoContent());
        rule("PICK-2", 1, 13, Map.of("multiple", 6));
        run();
        assertThat(plans()).containsExactly("12 RS-1/P1 BELOW_MINIMUM", "12 RS-1/P1 EMPTY");
        // The emptier one comes up first.
        call(get("/api/rf/replenishment").cookie(ann)).andExpect(jsonPath("$.waitingCount").value(2))
                .andExpect(jsonPath("$.waiting[0].toLocation.locationId").value("PICK-2"));
    }

    @Test
    void onlyGoodFreeStockFromTheRightPlaceIsUsed() throws Exception {
        String zone = body(call(json(post("/api/zones"), Map.of("function", "REPLENISHMENT", "code", "BULK", "name", "Bulk", "status", "ACTIVE")))
                .andExpect(status().isCreated())).get("id").asString();
        call(json(post("/api/zones/" + zone + "/locations"), Map.of("filter", Map.of("locationIds", List.of("RS-2"))))).andExpect(status().isOk());
        rule("PICK-1", 50, 60, Map.of("sourceZoneId", zone));
        JsonNode result = run();
        assertThat(result.get("quantity").asInt()).isEqualTo(30);
        assertThat(result.get("still").asInt()).as("no more in the zone").isEqualTo(1);
        assertThat(plans()).containsExactly("30 RS-2/null BELOW_MINIMUM");
        call(delete("/api/zones/" + zone)).andExpect(status().isConflict());

        // Anywhere, but not from another pick location, held stock, or stock reserved for an order.
        cancelAll();
        rule("PICK-2", 0, 100, Map.of());
        hold("RS-2");
        order("SO-1", 30);
        save(ruleId("PICK-1"), "PICK-1", 10, 60, Map.of("version", 0)).andExpect(status().isOk());
        run();
        assertThat(plans()).containsExactly("15 RS-1/P1 BELOW_MINIMUM");
    }

    @Test
    void ordersThatAreShortAreFilledFor() throws Exception {
        keptFromOrders("RS-1", "RS-2");
        rule("PICK-1", 2, 20, Map.of("forOrders", true));
        JsonNode order = order("SO-1", 25);
        assertThat(order.get("status").asString()).isEqualTo("PARTIALLY_ALLOCATED");
        call(get("/api/replenishment/rules")).andExpect(jsonPath("$[0].waiting").value(20)).andExpect(jsonPath("$[0].reserved").value(5));
        assertThat(run().get("quantity").asInt()).as("20 for the order, and 20 on top").isEqualTo(40);
        assertThat(plans()).containsExactly("40 RS-1/P1 ORDERS_WAITING");

        // When it is there, the order gets it.
        start(ann, null).andExpect(jsonPath("$.task.quantity").value(40));
        confirm(ann, Map.of("quantity", 40)).andExpect(status().isOk()).andExpect(jsonPath("$.task").doesNotExist());
        call(get("/api/orders/" + order.get("id").asString())).andExpect(jsonPath("$.status").value("ALLOCATED"));
        assertThat(run().get("quantity").asInt()).isZero();
    }

    @Test
    void aLocationKeptFromOrdersIsNotFilledForThemEndlessly() throws Exception {
        keptFromOrders("PICK-1", "RS-1", "RS-2");
        rule("PICK-1", 2, 20, Map.of("forOrders", true));
        order("SO-1", 25);
        assertThat(run().get("quantity").asInt()).isZero();
    }

    // ---------- Doing it ----------

    @Test
    void stockIsMovedToThePickLocation() throws Exception {
        rule("PICK-1", 10, 50, Map.of());
        run();
        start(ann, null).andExpect(jsonPath("$.task.fromLocation.locationId").value("RS-1")).andExpect(jsonPath("$.task.fromLpn").value("P1"))
                .andExpect(jsonPath("$.task.scans", hasSize(3)));
        start(ann, null).andExpect(jsonPath("$.task.fromLpn").value("P1"));

        confirm(ann, Map.of("fromLocationId", "RS-2", "quantity", 12)).andExpect(jsonPath("$.errors.fromLocationId").exists());
        confirm(ann, Map.of("lpnId", "T1", "quantity", 12)).andExpect(jsonPath("$.errors.lpnId").exists());
        confirm(ann, Map.of("itemBarcode", "999", "quantity", 12)).andExpect(jsonPath("$.errors.itemBarcode").exists());
        confirm(ann, Map.of("quantity", 41)).andExpect(jsonPath("$.errors.quantity").exists());
        confirm(ann, Map.of("quantity", 0)).andExpect(jsonPath("$.errors.quantity").exists());
        confirm(ann, Map.of("toLocationId", "PICK-2", "quantity", 12)).andExpect(jsonPath("$.errors.toLocationId", containsString("PICK-1")));
        confirm(bob, Map.of("quantity", 12)).andExpect(status().isConflict());

        // Some now and the rest on another trip.
        confirm(ann, Map.of("itemBarcode", "333006", "quantity", 12)).andExpect(jsonPath("$.task.movedQuantity").value(12));
        confirm(ann, Map.of("itemBarcode", "333006", "quantity", 12)).andExpect(jsonPath("$.task.movedQuantity").value(12)); // sent twice
        assertThat(at("PICK-1")).isEqualTo(17);
        confirm(ann, Map.of("movedSoFar", 12, "quantity", 28)).andExpect(jsonPath("$.task").doesNotExist());
        confirm(ann, Map.of("movedSoFar", 12, "quantity", 28)).andExpect(status().isOk());
        assertThat(at("PICK-1")).isEqualTo(45);
        call(get("/api/replenishment/tasks").param("status", "COMPLETED")).andExpect(jsonPath("$.content[0].movedQuantity").value(40))
                .andExpect(jsonPath("$.content[0].assignedTo").value("Ann Lee"));
        // The pallet is used up, and the stock there is one record again.
        em.flush();
        em.clear();
        assertThat(containers.findByLpnId("P1").orElseThrow().getStatus()).isEqualTo(ContainerStatus.RETIRED);
        assertThat(inventory.findByLocation_IdIn(List.of(UUID.fromString(id("PICK-1"))))).hasSize(1);
        call(get("/api/inventory/history").param("type", "REPLENISH")).andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].fromLpn").value("P1")).andExpect(jsonPath("$.content[0].toLocation").value("PICK-1"));
    }

    @Test
    void whatIsMissingIsHeldAndFoundElsewhere() throws Exception {
        rule("PICK-1", 10, 45, Map.of());
        run();
        assertThat(plans()).containsExactly("40 RS-1/P1 BELOW_MINIMUM");
        start(ann, null);
        confirm(ann, Map.of("quantity", 10, "shortage", true)).andExpect(jsonPath("$.task").doesNotExist());
        assertThat(at("PICK-1")).isEqualTo(15);
        call(get("/api/inventory/on-hand").param("q", "SKU-CUP")).andExpect(jsonPath("$.content[0].total.onHold").value(30));
        call(get("/api/replenishment/tasks").param("status", "COMPLETED")).andExpect(jsonPath("$.content[0].shortQuantity").value(30));
        // 15 is above the minimum, so it is left at that until it drops.
        assertThat(plans()).isEmpty();
        order("SO-1", 8);
        run();
        assertThat(plans()).containsExactly("30 RS-2/null BELOW_MINIMUM");
        // None at all.
        start(ann, null);
        Map<String, Object> none = new HashMap<>(Map.of("fromLocationId", "RS-2", "quantity", 0, "shortage", true));
        none.put("itemBarcode", null);
        confirm(ann, none).andExpect(status().isOk()).andExpect(jsonPath("$.task").doesNotExist());
        assertThat(inventory.findByLocation_IdIn(List.of(UUID.fromString(id("RS-2")))).getFirst().getStatus()).isEqualTo(InventoryStatus.ON_HOLD);
    }

    @Test
    void aTaskIsGivenBackOrCancelled() throws Exception {
        rule("PICK-1", 10, 50, Map.of());
        run();
        String first = start(ann, null).andReturn().getResponse().getContentAsString();
        String id = mapper.readTree(first).get("task").get("id").asString();
        start(bob, id).andExpect(status().isConflict());
        call(get("/api/users")).andExpect(status().isOk());
        start(bob, null).andExpect(jsonPath("$.task.fromLocation.locationId").value("RS-2"));
        call(post("/api/rf/replenishment/tasks/" + id + "/release").cookie(ann)).andExpect(jsonPath("$.task").doesNotExist())
                .andExpect(jsonPath("$.waitingCount").value(1));
        start(ann, id).andExpect(jsonPath("$.task.id").value(id));
        lastTask = id;
        call(post("/api/replenishment/tasks/" + id + "/cancel")).andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.note", containsString("Cancelled by")));
        confirm(ann, Map.of("quantity", 5)).andExpect(status().isConflict());
        call(post("/api/replenishment/tasks/" + id + "/cancel")).andExpect(status().isConflict());
        start(ann, null).andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value("No replenishment is waiting."));
    }

    @Test
    void aFullLocationAsksFirst() throws Exception {
        locations.findByLocationId("PICK-1").orElseThrow().setMaxUnits(30);
        rule("PICK-1", 10, 50, Map.of());
        run();
        start(ann, null);
        confirm(ann, Map.of("quantity", 40)).andExpect(status().isConflict()).andExpect(jsonPath("$.errors.capacity").exists());
        confirm(ann, Map.of("quantity", 40, "allowOver", true)).andExpect(status().isOk());
        assertThat(at("PICK-1")).isEqualTo(45);
    }

    // ---------- Along with everything else ----------

    @Test
    void ordersTakeBackWhatNobodyHasStartedToMove() throws Exception {
        rule("PICK-1", 10, 50, Map.of());
        run();
        start(ann, null); // 40 on P1
        // 30 free at first: 5 at the pick location and 25 at RS-2. The 5 planned there are taken back; what Ann moves stays.
        JsonNode order = order("SO-1", 70);
        assertThat(order.get("status").asString()).isEqualTo("PARTIALLY_ALLOCATED");
        assertThat(order.get("lines").get(0).get("allocatedQuantity").asInt()).isEqualTo(35);
        call(get("/api/replenishment/tasks").param("status", "CANCELLED")).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].note", containsString("SO-1")));
        confirm(ann, Map.of("quantity", 40)).andExpect(status().isOk());
        call(get("/api/orders/" + order.get("id").asString())).andExpect(jsonPath("$.status").value("ALLOCATED"));
    }

    @Test
    void holdsCountsMovesAndCorrectionsCallItOff() throws Exception {
        rule("PICK-1", 10, 75, Map.of());
        run();
        assertThat(plans()).hasSize(2);
        hold("RS-2");
        assertThat(plans()).containsExactly("40 RS-1/P1 BELOW_MINIMUM");
        call(json(post("/api/rf/moves/lpn"), Map.of("lpnId", "P1", "toLocationId", "PICK-2")).cookie(bob)).andExpect(status().isOk());
        assertThat(plans()).isEmpty();
        run();
        assertThat(plans()).containsExactly("40 PICK-2/P1 BELOW_MINIMUM");
        JsonNode p1 = body(call(get("/api/inventory/stock").param("q", "P1"))).get("content").get(0);
        call(json(post("/api/inventory/adjustments/change"), Map.of("inventoryId", p1.get("id").asString(), "expectedQuantity", 40,
                "newQuantity", 38, "reasonId", DAMAGED))).andExpect(status().isOk());
        assertThat(plans()).isEmpty();

        // Being moved, it is left alone.
        run();
        start(ann, null).andExpect(jsonPath("$.task.quantity").value(38));
        call(json(post("/api/rf/moves/lpn"), Map.of("lpnId", "P1", "toLocationId", "RS-1")).cookie(bob)).andExpect(status().isConflict());
        // Held while someone moves it: a count there has to wait, it can't go, and is called off when given back.
        hold("PICK-2");
        confirm(ann, Map.of("fromLocationId", "PICK-2", "quantity", 38)).andExpect(status().isConflict()).andExpect(jsonPath("$.detail", containsString("on hold")));
        String held = taskOf(ann);
        // (A count that can't start is undone, but not within one test transaction, so it comes last.)
        call(json(post("/api/inventory/counts"), Map.of("locationId", "PICK-2"))).andExpect(status().isOk());
        call(json(post("/api/rf/counts/start"), Map.of("locationId", "PICK-2")).cookie(bob)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("moving stock there")));
        call(post("/api/rf/replenishment/tasks/" + held + "/release").cookie(ann)).andExpect(jsonPath("$.waitingCount").value(0));
    }

    @Test
    void nothingIsMovedWhereItIsCounted() throws Exception {
        rule("PICK-1", 10, 50, Map.of());
        run();
        call(json(post("/api/inventory/counts"), Map.of("locationId", "RS-1"))).andExpect(status().isOk());
        call(json(post("/api/rf/counts/start"), Map.of("locationId", "RS-1")).cookie(bob)).andExpect(status().isOk());
        start(ann, null).andExpect(jsonPath("$.task.fromLocation.locationId").value("RS-2"));
        call(post("/api/rf/replenishment/tasks/" + taskOf(ann) + "/release").cookie(ann)).andExpect(status().isOk());
        call(json(post("/api/inventory/counts"), Map.of("locationId", "PICK-1"))).andExpect(status().isOk());
        call(json(post("/api/rf/counts/start"), Map.of("locationId", "PICK-1", "takeOver", true)).cookie(ann)).andExpect(status().isOk());
        cancelAll();
        assertThat(run().get("quantity").asInt()).as("not while the pick location is counted").isZero();
    }

    @Test
    void stockThatExpiresIsCalledOff() throws Exception {
        rule("PICK-1", 11, 50, Map.of());
        run();
        em.createQuery("update InventoryRecord i set i.expiryDate = :d where i.quantity = 40").setParameter("d", LocalDate.now().minusDays(1)).executeUpdate();
        run();
        assertThat(plans()).containsExactly("5 RS-2/null BELOW_MINIMUM", "25 RS-2/null BELOW_MINIMUM");
    }

    // ---------- Helpers ----------

    private String rule(String location, int min, int max, Map<String, Object> more) throws Exception {
        return body(save(null, location, min, max, more).andExpect(status().isCreated())).get("id").asString();
    }

    private ResultActions save(String id, String location, int min, int max, Map<String, Object> more) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("itemId", cups.getId(), "locationId", id(location), "minQuantity", min, "maxQuantity", max,
                "status", "ACTIVE"));
        body.putAll(more);
        return call(json(id == null ? post("/api/replenishment/rules") : put("/api/replenishment/rules/" + id), body));
    }

    private String ruleId(String location) throws Exception {
        for (JsonNode r : body(call(get("/api/replenishment/rules")))) {
            if (r.get("location").get("locationId").asString().equals(location)) {
                return r.get("id").asString();
            }
        }
        throw new AssertionError("no rule for " + location);
    }

    private JsonNode run() throws Exception {
        return body(call(post("/api/replenishment/run")).andExpect(status().isOk()));
    }

    /** Waiting tasks, oldest first: quantity, from where, and why. */
    private List<String> plans() throws Exception {
        List<String> result = new ArrayList<>();
        for (JsonNode t : body(call(get("/api/replenishment/tasks").param("status", "OPEN"))).get("content")) {
            result.addFirst(t.get("quantity").asInt() + " " + t.get("fromLocation").get("locationId").asString() + "/"
                    + (t.get("fromLpn").isNull() ? null : t.get("fromLpn").asString()) + " " + t.get("reason").asString());
        }
        return result;
    }

    private void cancelAll() throws Exception {
        for (JsonNode t : body(call(get("/api/replenishment/tasks").param("status", "OPEN"))).get("content")) {
            call(post("/api/replenishment/tasks/" + t.get("id").asString() + "/cancel")).andExpect(status().isOk());
        }
    }

    private ResultActions start(Cookie who, String taskId) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("taskId", taskId);
        return call(json(post("/api/rf/replenishment/start"), body).cookie(who));
    }

    private String taskOf(Cookie who) throws Exception {
        return body(call(get("/api/rf/replenishment").cookie(who))).get("task").get("id").asString();
    }

    private String lastTask;

    private ResultActions confirm(Cookie who, Map<String, Object> more) throws Exception {
        JsonNode task = body(call(get("/api/rf/replenishment").cookie(ann))).get("task");
        if (!task.isNull()) {
            lastTask = task.get("id").asString();
        }
        Map<String, Object> body = new HashMap<>(Map.of("movedSoFar", 0, "fromLocationId", "RS-1", "lpnId", "P1", "itemBarcode", "333000",
                "toLocationId", "PICK-1"));
        body.putAll(more);
        return call(json(post("/api/rf/replenishment/tasks/" + lastTask + "/confirm"), body).cookie(who));
    }

    private void hold(String location) throws Exception {
        call(json(post("/api/inventory/holds"), Map.of("scope", Map.of("locationId", location), "reasonId", RECALL))).andExpect(status().isOk());
    }

    private void keptFromOrders(String... at) throws Exception {
        String zone = body(call(json(post("/api/zones"), Map.of("function", "ALLOCATION", "code", "KEPT", "name", "Kept", "status", "ACTIVE",
                "allocatable", false))).andExpect(status().isCreated())).get("id").asString();
        call(json(post("/api/zones/" + zone + "/locations"), Map.of("filter", Map.of("locationIds", List.of(at))))).andExpect(status().isOk());
    }

    private JsonNode order(String number, int quantity) throws Exception {
        return body(call(json(post("/api/orders"), Map.of("orderNumber", number, "customerName", "Cafe",
                "lines", List.of(Map.of("itemId", "SKU-CUP", "quantity", quantity))))).andExpect(status().isCreated()));
    }

    private int at(String location) {
        em.flush();
        em.clear();
        return inventory.findByLocation_IdIn(List.of(UUID.fromString(id(location)))).stream().mapToInt(InventoryRecord::getQuantity).sum();
    }

    private String id(String location) {
        return locations.findByLocationId(location).orElseThrow().getId().toString();
    }

    private Cookie signIn(String user) throws Exception {
        return call(json(post("/api/rf/session"), Map.of("userCode", user, "pin", PIN))).andExpect(status().isOk()).andReturn().getResponse()
                .getCookie("wms_device");
    }

    /** Stores pending changes and forgets loaded entities first, so each request reads the database like a new one. */
    private ResultActions call(MockHttpServletRequestBuilder request) throws Exception {
        em.flush();
        em.clear();
        return mvc.perform(request);
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, Object body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
    }

    private JsonNode body(ResultActions result) throws Exception {
        return mapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private void stock(Item item, int quantity, int order, Consumer<InventoryRecord> place) {
        InventoryRecord r = new InventoryRecord(UUID.randomUUID(), item, quantity);
        r.setReceivedAt(Instant.parse("2026-01-0" + order + "T00:00:00Z"));
        place.accept(r);
        inventory.save(r);
    }

    private Item item(String code, String barcode) {
        Item i = new Item(UUID.randomUUID());
        i.setItemId(code);
        i.setDescription(code);
        i.setStatus(ActiveStatus.ACTIVE);
        i.setBarcode(barcode);
        return items.save(i);
    }

    private Location location(String code, LocationType type) {
        Location l = new Location(UUID.randomUUID());
        l.setLocationId(code);
        l.setType(type);
        l.setArea("DC1");
        l.setZone("Z");
        if (type == LocationType.STORAGE) {
            l.setAisle("01");
            l.setBay("01");
            l.setLevel("A");
        }
        l.setPosition(code);
        l.setWidth(BigDecimal.valueOf(100));
        l.setDepth(BigDecimal.valueOf(100));
        l.setHeight(BigDecimal.valueOf(100));
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
}
