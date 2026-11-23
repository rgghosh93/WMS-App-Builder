package com.wms.putaway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
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
import com.wms.users.AppUserRepository;
import com.wms.users.TestUsers;

import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PutawayTest {

    private static final String PIN = "1357";
    private static final String RCV = "DC1-RCV-01";
    private static final String RF = "/api/rf/putaway";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private EntityManager em;
    @Autowired private ItemRepository items;
    @Autowired private LocationRepository locations;
    @Autowired private InventoryRepository inventory;
    @Autowired private ContainerRepository containers;
    @Autowired private ContainerTypeRepository types;
    @Autowired private AppUserRepository users;
    @Autowired private PutawayTaskRepository tasks;
    @Autowired private TestUsers people;

    private Item cups;
    private Item nuts;
    private ContainerType pallet;
    private Cookie ann;
    private Cookie bob;
    private String asn;

    /** Racking RS-1..3 (one LPN, one item, 500 lb), shelving BK-1..2 (no limits), a pallet type and tote T1. */
    @BeforeEach
    void fixtures() throws Exception {
        location(RCV, LocationType.RECEIVING, "RCV", "01");
        for (String n : List.of("01", "02", "03")) {
            Location rack = location("DC1-RS-" + n, LocationType.STORAGE, "RS", n);
            rack.setMaxLpns(1);
            rack.setMaxSkus(1);
            rack.setMaxWeight(new BigDecimal("500"));
            rack.setMaxWeightUom(WeightUom.LB);
        }
        location("DC1-BK-01", LocationType.STORAGE, "BK", "01");
        location("DC1-BK-02", LocationType.STORAGE, "BK", "02");
        cups = item("SKU-CUP", "333000");
        cups.setWeight(BigDecimal.ONE);
        cups.setWeightUom(WeightUom.LB);
        nuts = item("SKU-NUT", "444000");
        nuts.setLotControlled(true);
        item("SKU-DRILL", "222000").setSerialControlled(true);
        pallet = type("PLT", ContainerCategory.PALLET, false);
        containers.save(tote("T1", type("TOTE", ContainerCategory.TOTE, true)));
        people.operator("ANN", "Ann Lee", PIN);
        people.operator("BOB", "Bob Ray", PIN);
        ann = signIn("ann");
        bob = signIn("bob");
        asn = mapper.readTree(call(json(post("/api/asns"), Map.of("asnNumber", "ASN-1", "vendorName", "Acme", "lines", List.of(
                Map.of("itemId", "SKU-CUP", "quantity", 10000), Map.of("itemId", "SKU-NUT", "quantity", 10000),
                Map.of("itemId", "SKU-DRILL", "quantity", 100)))), null).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asString();
    }

    // ---------- Tasks ----------

    @Test
    void receivingMakesOneTaskPerLpn() throws Exception {
        mvc.perform(get(RF)).andExpect(status().isUnauthorized());
        receive("LP1", "333000", 5, null);
        receive("LP1", "444000", 2, "L1");
        receive("LP2", "333000", 5, null);
        call(get("/api/putaway/tasks"), null).andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[*].taskNumber", everyItem(matchesPattern("PA\\d{6}"))));
        for (String q : List.of("lp1", "sku-nut")) {
            call(get("/api/putaway/tasks").param("q", q), null).andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.content[0].totalQuantity").value(7))
                    .andExpect(jsonPath("$.content[0].fromLocation.locationId").value(RCV))
                    .andExpect(jsonPath("$.content[0].status").value("OPEN"));
        }
        call(get("/api/putaway/tasks").param("status", "COMPLETED"), null).andExpect(jsonPath("$.totalElements").value(0));
        call(get("/api/putaway/tasks/" + UUID.randomUUID()), null).andExpect(status().isNotFound());
        call(get(RF), ann).andExpect(jsonPath("$.waitingCount").value(2)).andExpect(jsonPath("$.waiting[0].lpn.lpnId").value("LP1"));
    }

    @Test
    void undoingEverythingInAnLpnCancelsItsTask() throws Exception {
        String receipt = receive("T1", "333000", 5, null).get("receipts").get(0).get("id").asString();
        call(post("/api/rf/receiving/receipts/" + receipt + "/reverse"), ann).andExpect(status().isOk());
        call(get(RF), ann).andExpect(jsonPath("$.waitingCount").value(0));
        call(get("/api/putaway/tasks"), null).andExpect(jsonPath("$.content[0].status").value("CANCELLED"));
        receive("T1", "333000", 5, null);
        call(get(RF), ann).andExpect(jsonPath("$.waitingCount").value(1));
    }

    @Test
    void whatWasReceivedAtADoorIsPutAwayButNotWhatElseStandsThere() throws Exception {
        Location door = location("DC1-DOCK-01", LocationType.DOCK, "DOCK", "01");
        rule(10, "All", Map.of("strategy", "NEAREST_EMPTY"));
        receive("LP1", "333000", 5, null, "AVAILABLE", List.of(), "DC1-DOCK-01");
        order().andExpect(jsonPath("$.status").value("BACKORDERED"));
        // A carton loaded for shipping.
        Container carton = tote("CTN1", pallet);
        carton.setPermanent(false);
        carton.setStatus(ContainerStatus.IN_USE);
        carton.setCurrentLocation(door);
        containers.save(carton);
        InventoryRecord stock = new InventoryRecord(UUID.randomUUID(), cups, 3);
        stock.placeIn(carton);
        inventory.save(stock);
        startRaw(ann, "CTN1", false).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.lpnId", containsString("was not received at DC1-DOCK-01")));

        JsonNode task = start(ann, "LP1");
        assertThat(task.get("fromLocation").get("locationId").asString()).isEqualTo("DC1-DOCK-01");
        call(post(RF + "/tasks/" + task.get("id").asString() + "/release"), ann).andExpect(status().isOk());
        assertThat(container("LP1").getCurrentLocation().getLocationId()).isEqualTo("DC1-DOCK-01");
        task = start(ann, "LP1");
        confirm(ann, task, task.get("directedLocation").get("locationId").asString(), Map.of()).andExpect(status().isOk());
        order().andExpect(jsonPath("$.status").value("ALLOCATED"));
    }

    // ---------- Where to ----------

    @Test
    void palletsGoToTheFirstEmptyRackAndStayOnTheirLpn() throws Exception {
        rule(10, "Pallets", Map.of("containerCategory", "PALLET", "strategy", "ZONE", "area", "dc1", "zone", "rs"));
        receive("LP1", "333000", 5, null);
        JsonNode task = start(ann, "lp1");
        assertThat(task.get("directedLocation").get("locationId").asString()).isEqualTo("DC1-RS-01");
        assertThat(task.get("ruleName").asString()).isEqualTo("Pallets");
        assertThat(container("LP1").getCurrentLocation()).isNull();
        confirm(ann, task, "dc1-rs-01", Map.of()).andExpect(status().isOk()).andExpect(jsonPath("$.task").doesNotExist());
        assertThat(container("LP1").getCurrentLocation().getLocationId()).isEqualTo("DC1-RS-01");
        assertThat(container("LP1").getStatus()).isEqualTo(ContainerStatus.IN_USE);
        call(get("/api/putaway/tasks/" + task.get("id").asString()), null)
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.toLocation.locationId").value("DC1-RS-01"))
                .andExpect(jsonPath("$.assignedTo").value("Ann Lee"))
                .andExpect(jsonPath("$.lines[0].quantity").value(5));
        // Sent twice: nothing more happens. The rack is full now, so the next pallet goes to the next one.
        confirm(ann, task, "DC1-RS-01", Map.of()).andExpect(status().isOk());
        receive("LP2", "333000", 5, null);
        assertThat(start(ann, "LP2").get("directedLocation").get("locationId").asString()).isEqualTo("DC1-RS-02");
    }

    @Test
    void putAwayStockCanBeAllocated() throws Exception {
        rule(10, "All", Map.of("strategy", "NEAREST_EMPTY"));
        receive("LP1", "333000", 5, null);
        order().andExpect(jsonPath("$.status").value("BACKORDERED"));
        JsonNode task = start(ann, "LP1");
        order().andExpect(jsonPath("$.status").value("BACKORDERED"));
        confirm(ann, task, "DC1-BK-01", Map.of()).andExpect(status().isOk());
        order().andExpect(jsonPath("$.status").value("ALLOCATED"));
    }

    @Test
    void twoPeopleAreNotSentToTheSameSpace() throws Exception {
        rule(10, "Racks", Map.of("strategy", "ZONE", "area", "DC1", "zone", "RS"));
        receive("LP1", "333000", 5, null);
        receive("LP2", "333000", 5, null);
        assertThat(start(ann, "LP1").get("directedLocation").get("locationId").asString()).isEqualTo("DC1-RS-01");
        assertThat(start(bob, "LP2").get("directedLocation").get("locationId").asString()).isEqualTo("DC1-RS-02");
    }

    @Test
    void rulesAreTriedInOrderUntilOneFitsAndHasRoom() throws Exception {
        rule(10, "Off", Map.of("strategy", "FIXED", "locationId", id("DC1-BK-02"), "status", "INACTIVE"));
        rule(20, "Nuts home", Map.of("strategy", "FIXED", "locationId", id("DC1-BK-02"), "itemId", nuts.getId().toString()));
        rule(30, "Damaged", Map.of("strategy", "FIXED", "locationId", id("DC1-RS-03"), "stockStatus", "DAMAGED"));
        rule(40, "Racks", Map.of("strategy", "ZONE", "area", "DC1", "zone", "RS"));
        rule(50, "Shelves", Map.of("strategy", "ZONE", "area", "DC1", "zone", "BK", "handling", "EMPTY_LPN"));
        receive("NUTS", "444000", 5, "L1");
        directed("NUTS", "Nuts home", "DC1-BK-02", "KEEP_LPN");
        receive("BAD", "333000", 5, null, "DAMAGED");
        directed("BAD", "Damaged", "DC1-RS-03", "KEEP_LPN");
        receive("HEAVY", "333000", 600, null);
        directed("HEAVY", "Shelves", "DC1-BK-01", "EMPTY_LPN");
        receive("MIXED", "333000", 5, null);
        receive("MIXED", "444000", 5, "L1");
        directed("MIXED", "Shelves", "DC1-BK-01", "EMPTY_LPN");
        receive("LIGHT", "333000", 5, null);
        directed("LIGHT", "Racks", "DC1-RS-01", "KEEP_LPN");
    }

    @Test
    void inAZoneTheSameStockGoesTogetherAndOtherLotsApart() throws Exception {
        rule(10, "Shelves", Map.of("strategy", "ZONE", "area", "DC1", "zone", "BK", "handling", "EMPTY_LPN"));
        receive("LP1", "444000", 5, "L1");
        confirm(ann, start(ann, "LP1"), "DC1-BK-01", Map.of()).andExpect(status().isOk());
        receive("LP2", "444000", 7, "L2");
        directed("LP2", "Shelves", "DC1-BK-02", "EMPTY_LPN");
        receive("LP3", "444000", 3, "L1");
        JsonNode task = start(ann, "LP3");
        confirm(ann, task, "DC1-BK-01", Map.of()).andExpect(status().isOk());
        assertThat(inventory.findByLocation_IdIn(List.of(locations.findByLocationId("DC1-BK-01").orElseThrow().getId())))
                .extracting(InventoryRecord::getQuantity).containsExactly(8);
        assertThat(container("LP3").getStatus()).isEqualTo(ContainerStatus.RETIRED);
    }

    // ---------- Emptying ----------

    @Test
    void emptyingAToteMakesItReadyAgain() throws Exception {
        rule(10, "Shelves", Map.of("strategy", "ZONE", "area", "DC1", "zone", "BK", "handling", "EMPTY_LPN"));
        receive("T1", "333000", 5, null);
        confirm(ann, start(ann, "T1"), "DC1-BK-01", Map.of()).andExpect(status().isOk());
        assertThat(container("T1").getStatus()).isEqualTo(ContainerStatus.AVAILABLE);
        assertThat(container("T1").getCurrentLocation()).isNull();
        receive("T1", "333000", 5, null);
        // The person can choose to keep it whole instead.
        confirm(ann, start(ann, "T1"), "DC1-BK-01", Map.of("handling", "KEEP_LPN")).andExpect(status().isOk());
        assertThat(container("T1").getCurrentLocation().getLocationId()).isEqualTo("DC1-BK-01");
        call(get("/api/putaway/tasks").param("status", "COMPLETED"), null).andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void serialNumbersStaySeparate() throws Exception {
        receive("LP1", "222000", 2, null, null, List.of("D1", "D2"));
        confirm(ann, start(ann, "LP1"), "DC1-BK-01", Map.of("handling", "EMPTY_LPN")).andExpect(status().isOk());
        assertThat(inventory.findByLocation_IdIn(List.of(locations.findByLocationId("DC1-BK-01").orElseThrow().getId())))
                .extracting(InventoryRecord::getSerialNumber).containsExactlyInAnyOrder("D1", "D2");
    }

    // ---------- Going elsewhere ----------

    @Test
    void anotherLocationNeedsAReason() throws Exception {
        rule(10, "Racks", Map.of("strategy", "ZONE", "area", "DC1", "zone", "RS"));
        receive("LP1", "333000", 5, null);
        JsonNode task = start(ann, "LP1");
        confirm(ann, task, "NOPE", Map.of()).andExpect(jsonPath("$.errors.locationId").value("NOPE is not a known location"));
        confirm(ann, task, RCV, Map.of()).andExpect(jsonPath("$.errors.locationId", containsString("not a storage location")));
        confirm(ann, task, "DC1-RS-03", Map.of()).andExpect(status().isConflict()).andExpect(jsonPath("$.errors.directed").value("DC1-RS-01"));
        confirm(ann, task, "DC1-RS-03", Map.of("reason", "OTHER")).andExpect(status().isOk());
        call(get("/api/putaway/tasks/" + task.get("id").asString()), null)
                .andExpect(jsonPath("$.overrideReason").value("OTHER"))
                .andExpect(jsonPath("$.directedLocation.locationId").value("DC1-RS-01"))
                .andExpect(jsonPath("$.toLocation.locationId").value("DC1-RS-03"));
    }

    @Test
    void aLocationThatCannotBeUsedIsPassedOver() throws Exception {
        rule(10, "Racks", Map.of("strategy", "ZONE", "area", "DC1", "zone", "RS"));
        receive("LP1", "333000", 5, null);
        JsonNode task = start(ann, "LP1");
        redirect(task, "DC1-RS-01", null).andExpect(status().isBadRequest());
        for (int sentTwice = 0; sentTwice < 2; sentTwice++) {
            redirect(task, "DC1-RS-01", "BLOCKED").andExpect(jsonPath("$.task.directedLocation.locationId").value("DC1-RS-02"));
        }
        redirect(task, "DC1-RS-02", "FULL").andExpect(jsonPath("$.task.directedLocation.locationId").value("DC1-RS-03"));
        redirect(task, "DC1-RS-03", "FULL").andExpect(jsonPath("$.task.directedLocation").doesNotExist())
                .andExpect(jsonPath("$.task.skips", hasSize(3)));
        // With nowhere to suggest, anywhere in storage will do.
        confirm(ann, task, "DC1-BK-01", Map.of()).andExpect(status().isOk());
    }

    @Test
    void overfillingIsConfirmedButTooHeavyIsRefused() throws Exception {
        receive("LP1", "333000", 400, null);
        confirm(ann, start(ann, "LP1"), "DC1-RS-01", Map.of()).andExpect(status().isOk());
        receive("LP2", "333000", 50, null);
        JsonNode task = start(ann, "LP2");
        assertThat(task.get("directedLocation").isNull()).isTrue();
        confirm(ann, task, "DC1-RS-01", Map.of()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors.capacity").value("DC1-RS-01 has no room for another LPN (it holds 1)"));
        confirm(ann, task, "DC1-RS-01", Map.of("allowOver", true)).andExpect(status().isOk());
        receive("LP3", "333000", 100, null);
        confirm(ann, start(ann, "LP3"), "DC1-RS-01", Map.of("allowOver", true)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.locationId", containsString("can't take the weight")));
    }

    // ---------- Who has it ----------

    @Test
    void onlyWaitingLpnsCanBeStartedOneAtATime() throws Exception {
        receive("LP1", "333000", 5, null);
        receive("LP2", "333000", 5, null);
        startRaw(ann, "", false).andExpect(status().isBadRequest());
        startRaw(ann, "NOPE", false).andExpect(jsonPath("$.errors.lpnId").value("NOPE is not a known LPN"));
        startRaw(ann, "T1", false).andExpect(status().isBadRequest());
        JsonNode task = start(ann, "LP1");
        assertThat(start(ann, "LP1").get("id")).isEqualTo(task.get("id"));
        startRaw(ann, "LP2", false).andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value("Put away LP1 first."));
        confirm(ann, task, "DC1-BK-01", Map.of()).andExpect(status().isOk());
        startRaw(ann, "LP1", false).andExpect(jsonPath("$.errors.lpnId").value("LP1 is already at DC1-BK-01"));
    }

    @Test
    void someoneElseCanTakeOver() throws Exception {
        receive("LP1", "333000", 5, null);
        JsonNode task = start(ann, "LP1");
        startRaw(bob, "LP1", false).andExpect(status().isConflict()).andExpect(jsonPath("$.errors.assignedTo").value("Ann Lee"));
        startRaw(bob, "LP1", true).andExpect(jsonPath("$.task.assignedTo").value("Bob Ray"));
        confirm(ann, task, "DC1-BK-01", Map.of()).andExpect(status().isConflict());
        call(get(RF), ann).andExpect(jsonPath("$.task").doesNotExist());
        confirm(bob, task, "DC1-BK-01", Map.of()).andExpect(status().isOk());
    }

    @Test
    void whatSomeoneHasInHandIsShownAndTheyCannotBeDeleted() throws Exception {
        receive("LP1", "333000", 5, null);
        JsonNode task = start(ann, "LP1");
        String user = "/api/users/" + users.findByUserCode("ANN").orElseThrow().getId();
        call(get(user), null).andExpect(jsonPath("$.inHand[0]").value("Putting away LP1"));
        confirm(ann, task, "DC1-BK-01", Map.of()).andExpect(status().isOk());
        call(get(user), null).andExpect(jsonPath("$.inHand", hasSize(0)));
        call(delete(user), null).andExpect(status().isConflict()).andExpect(jsonPath("$.detail", containsString("Mark them Inactive")));
    }

    @Test
    void anLpnCanBeGivenBack() throws Exception {
        rule(10, "Racks", Map.of("strategy", "ZONE", "area", "DC1", "zone", "RS"));
        receive("LP1", "333000", 5, null);
        JsonNode task = start(ann, "LP1");
        // Nothing more can be received into it, or undone, while someone has it.
        call(json(post("/api/rf/receiving/asns/" + asn + "/lpn"), Map.of("locationId", RCV, "lpnId", "LP1")), ann).andExpect(status().isBadRequest());
        call(post(RF + "/tasks/" + task.get("id").asString() + "/release"), ann).andExpect(jsonPath("$.waitingCount").value(1));
        assertThat(container("LP1").getCurrentLocation().getLocationId()).isEqualTo(RCV);
        assertThat(tasks.findById(UUID.fromString(task.get("id").asString())).orElseThrow().getDirectedLocation()).isNull();
        receive("LP1", "333000", 5, null);
        receive("LP2", "333000", 5, null);
        assertThat(start(bob, "LP2").get("directedLocation").get("locationId").asString()).isEqualTo("DC1-RS-01");
    }

    // ---------- Rules ----------

    @Test
    void rulesAreKept() throws Exception {
        rule(20, "Second", Map.of("strategy", "NEAREST_EMPTY"));
        JsonNode first = rule(10, "First", Map.of("strategy", "FIXED", "locationId", id("DC1-BK-01"), "itemId", cups.getId().toString()));
        call(get("/api/putaway/rules"), null).andExpect(jsonPath("$[0].name").value("First")).andExpect(jsonPath("$[0].location.locationId").value("DC1-BK-01"));
        call(delete("/api/items/" + cups.getId()), null).andExpect(status().isConflict());
        call(delete("/api/locations/" + id("DC1-BK-01")), null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("1 put-away rule uses it")));
        String url = "/api/putaway/rules/" + first.get("id").asString();
        Map<String, Object> body = ruleBody(30, "First", Map.of("strategy", "ZONE", "area", "DC1", "zone", "BK"));
        call(json(put(url), body), null).andExpect(status().isBadRequest());
        body.put("version", 99);
        call(json(put(url), body), null).andExpect(status().isConflict());
        body.put("version", first.get("version").asLong());
        call(json(put(url), body), null).andExpect(status().isOk()).andExpect(jsonPath("$.location").doesNotExist());
        call(get("/api/putaway/rules"), null).andExpect(jsonPath("$[0].name").value("Second"));
        call(delete(url), null).andExpect(status().isNoContent());
        call(get(url), null).andExpect(status().isNotFound());
    }

    @Test
    void rulesAreChecked() throws Exception {
        rule(10, "First", Map.of("strategy", "NEAREST_EMPTY"));
        call(json(post("/api/putaway/rules"), ruleBody(10, "first", Map.of("strategy", "FIXED", "locationId", id(RCV)))), null)
                .andExpect(jsonPath("$.errors.name").exists()).andExpect(jsonPath("$.errors.locationId", containsString("not a storage")));
        call(json(post("/api/putaway/rules"), ruleBody(10, "X", Map.of("strategy", "FIXED"))), null).andExpect(jsonPath("$.errors.locationId").exists());
        call(json(post("/api/putaway/rules"), ruleBody(10, "X", Map.of("strategy", "ZONE"))), null).andExpect(jsonPath("$.errors.zoneId").exists());
        call(json(post("/api/putaway/rules"), ruleBody(10, "X", Map.of("strategy", "NEAREST_EMPTY", "zoneId", UUID.randomUUID()))), null)
                .andExpect(jsonPath("$.errors.zoneId").exists());
        call(json(post("/api/putaway/rules"), ruleBody(0, " ", Map.of("strategy", "NEAREST_EMPTY"))), null)
                .andExpect(jsonPath("$.errors.sequence").exists()).andExpect(jsonPath("$.errors.name").exists());
    }

    // ---------- Helpers ----------

    private JsonNode receive(String lpn, String barcode, int quantity, String lot) throws Exception {
        return receive(lpn, barcode, quantity, lot, "AVAILABLE");
    }

    private JsonNode receive(String lpn, String barcode, int quantity, String lot, String condition) throws Exception {
        return receive(lpn, barcode, quantity, lot, condition, List.of());
    }

    private JsonNode receive(String lpn, String barcode, int quantity, String lot, String condition, List<String> serials) throws Exception {
        return receive(lpn, barcode, quantity, lot, condition, serials, RCV);
    }

    private JsonNode receive(String lpn, String barcode, int quantity, String lot, String condition, List<String> serials,
                             String at) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("requestId", UUID.randomUUID(), "locationId", at, "lpnId", lpn,
                "containerTypeId", pallet.getId(), "itemBarcode", barcode, "quantity", quantity, "serialNumbers", serials));
        body.put("lotNumber", lot);
        body.put("condition", condition);
        return body(call(json(post("/api/rf/receiving/asns/" + asn + "/receive"), body), ann));
    }

    private ResultActions startRaw(Cookie user, String lpn, boolean takeOver) throws Exception {
        return call(json(post(RF + "/start"), Map.of("lpnId", lpn, "takeOver", takeOver)), user);
    }

    private JsonNode start(Cookie user, String lpn) throws Exception {
        return body(startRaw(user, lpn, false)).get("task");
    }

    private ResultActions confirm(Cookie user, JsonNode task, String location, Map<String, Object> more) throws Exception {
        Map<String, Object> body = new HashMap<>(more);
        body.put("locationId", location);
        return call(json(post(RF + "/tasks/" + task.get("id").asString() + "/confirm"), body), user);
    }

    private ResultActions redirect(JsonNode task, String location, String reason) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("locationId", location);
        body.put("reason", reason);
        return call(json(post(RF + "/tasks/" + task.get("id").asString() + "/redirect"), body), ann);
    }

    /** Where an LPN would go; it is given back afterwards. */
    private void directed(String lpn, String rule, String location, String handling) throws Exception {
        JsonNode task = start(ann, lpn);
        assertThat(task.get("ruleName").asString()).isEqualTo(rule);
        assertThat(task.get("directedLocation").get("locationId").asString()).isEqualTo(location);
        assertThat(task.get("handling").asString()).isEqualTo(handling);
        call(post(RF + "/tasks/" + task.get("id").asString() + "/release"), ann).andExpect(status().isOk());
    }

    private Map<String, Object> ruleBody(int sequence, String name, Map<String, Object> more) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("sequence", sequence, "name", name, "status", "ACTIVE", "handling", "KEEP_LPN"));
        body.putAll(more);
        // Tests name an address zone; a put-away zone of its locations stands for it.
        Object zone = body.remove("zone");
        if (zone != null) {
            body.put("zoneId", putawayZone(String.valueOf(body.remove("area")), zone.toString()));
        }
        return body;
    }

    private String putawayZone(String area, String zone) throws Exception {
        for (JsonNode z : body(call(get("/api/zones").param("function", "PUTAWAY"), null))) {
            if (z.get("code").asString().equals(zone.toUpperCase())) {
                return z.get("id").asString();
            }
        }
        String id = mapper.readTree(call(json(post("/api/zones"), Map.of("function", "PUTAWAY", "code", zone, "name", zone, "status", "ACTIVE")), null)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asString();
        call(json(post("/api/zones/" + id + "/locations"), Map.of("filter", Map.of("area", area, "zone", zone))), null).andExpect(status().isOk());
        return id;
    }

    private JsonNode rule(int sequence, String name, Map<String, Object> more) throws Exception {
        return mapper.readTree(call(json(post("/api/putaway/rules"), ruleBody(sequence, name, more)), null)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private ResultActions order() throws Exception {
        return call(json(post("/api/orders"), Map.of("customerName", "Cafe", "lines", List.of(Map.of("itemId", "SKU-CUP", "quantity", 1)))), null);
    }

    private String id(String location) {
        return locations.findByLocationId(location).orElseThrow().getId().toString();
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
