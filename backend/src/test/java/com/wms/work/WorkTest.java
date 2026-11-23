package com.wms.work;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
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
import com.wms.inbound.ReceivedLpns;
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
class WorkTest {

    private static final String PIN = "8264";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private EntityManager em;
    @Autowired private ItemRepository items;
    @Autowired private LocationRepository locations;
    @Autowired private InventoryRepository inventory;
    @Autowired private ContainerRepository containers;
    @Autowired private ContainerTypeRepository types;
    @Autowired private AppUserRepository users;
    @Autowired private ReceivedLpns received;
    @Autowired private TestUsers people;

    private Item cups;
    private Cookie ann;
    private Cookie bob;

    /**
     * One task of each kind that has one by itself: put-away of LP1 at RCV-1 (5), replenishment of PICK-1 from RS-1 (5, 4 as it
     * is empty), and a count at BK-1 (7). Orders add picks.
     */
    @BeforeEach
    void fixtures() throws Exception {
        cups = item("SKU-CUP", "333000");
        Location rcv = location("RCV-1", LocationType.RECEIVING, "RCV", null);
        Location rs = location("RS-1", LocationType.STORAGE, "RS", "01");
        location("PICK-1", LocationType.STORAGE, "PK", "01");
        Location bk = location("BK-1", LocationType.STORAGE, "BK", "01");
        location("DC1-PACK-01", LocationType.PACKING, "PACK", null);
        ContainerType pallet = type("PLT", ContainerCategory.PALLET, false);
        Container lp1 = lpn("LP1", pallet, ContainerStatus.IN_USE, false);
        lp1.setCurrentLocation(rcv);
        Container lp = containers.save(lp1);
        stock(5, 1, r -> r.placeIn(lp));
        stock(50, 2, r -> r.placeIn(rs));
        stock(30, 3, r -> r.placeIn(bk));
        ContainerType tote = type("TOTE", ContainerCategory.TOTE, true);
        containers.save(lpn("T1", tote, ContainerStatus.AVAILABLE, true));
        containers.save(lpn("T2", tote, ContainerStatus.AVAILABLE, true));
        received.received(lp, rcv);
        people.operator("ANN", "Ann Lee", PIN);
        people.operator("BOB", "Bob Ray", PIN);
        ann = signIn("ANN");
        bob = signIn("BOB");
        call(json(post("/api/replenishment/rules"), Map.of("itemId", cups.getId(), "locationId", locations.findByLocationId("PICK-1").orElseThrow().getId(),
                "minQuantity", 5, "maxQuantity", 10, "status", "ACTIVE"))).andExpect(status().isCreated());
        call(post("/api/replenishment/run")).andExpect(jsonPath("$.quantity").value(10));
        call(json(post("/api/inventory/counts"), Map.of("locationId", "BK-1"))).andExpect(status().isOk());
    }

    // ---------- The queue ----------

    @Test
    void allWorkIsInOneQueueMostUrgentFirst() throws Exception {
        order("SO-1", 3, 2);
        assertThat(queue(Map.of())).containsExactly("PICK 3", "REPLENISHMENT 4", "PUTAWAY 5", "COUNT 7");
        call(get("/api/tasks")).andExpect(jsonPath("$.content[0].location").value("RS-1"))
                .andExpect(jsonPath("$.content[0].what", containsString("Pick SO-1: 1 pick")))
                .andExpect(jsonPath("$.content[1].what").value("Bring 10 EA of SKU-CUP to PICK-1"))
                .andExpect(jsonPath("$.content[2].lpn").value("LP1")).andExpect(jsonPath("$.content[2].location").value("RCV-1"))
                .andExpect(jsonPath("$.content[3].link", containsString("/inventory/counts/")));
        assertThat(queue(Map.of("kind", "COUNT"))).containsExactly("COUNT 7");
        assertThat(queue(Map.of("q", "lp1"))).containsExactly("PUTAWAY 5");
        assertThat(queue(Map.of("q", "nothing like it"))).isEmpty();
        call(get("/api/tasks").param("size", "2").param("page", "1")).andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.content", hasSize(2))).andExpect(jsonPath("$.content[0].kind").value("PUTAWAY"));
        call(get("/api/tasks/summary")).andExpect(jsonPath("$", hasSize(5))).andExpect(jsonPath("$[0].kind").value("PUTAWAY"))
                .andExpect(jsonPath("$[0].waiting").value(1)).andExpect(jsonPath("$[0].oldest").exists())
                .andExpect(jsonPath("$[4].kind").value("PACK")).andExpect(jsonPath("$[4].waiting").value(0));
    }

    @Test
    void whatIsStartedShowsWhoIsDoingIt() throws Exception {
        call(json(post("/api/rf/putaway/start"), Map.of("lpnId", "LP1")).cookie(ann)).andExpect(status().isOk());
        assertThat(queue(Map.of("state", "STARTED"))).containsExactly("PUTAWAY 5");
        call(get("/api/tasks").param("state", "STARTED")).andExpect(jsonPath("$.content[0].worker").value("Ann Lee"));
        assertThat(queue(Map.of("state", "WAITING"))).containsExactly("REPLENISHMENT 4", "COUNT 7");
        assertThat(queue(Map.of("person", people.idOf("ANN").toString()))).containsExactly("PUTAWAY 5");
        call(get("/api/tasks/people")).andExpect(jsonPath("$[?(@.name == 'Ann Lee')].doing[0]", hasItem(containsString("Put-away PA"))));
        // Reviewed counts and finished tasks aren't in it.
        call(json(post("/api/rf/counts/start"), Map.of("locationId", "BK-1")).cookie(bob)).andExpect(status().isOk());
        assertThat(queue(Map.of("kind", "COUNT", "state", "STARTED"))).hasSize(1);
        call(post("/api/rf/counts/" + id("COUNT") + "/finish").cookie(bob)).andExpect(status().isOk());
        assertThat(queue(Map.of("kind", "COUNT"))).isEmpty();
    }

    // ---------- Priorities ----------

    @Test
    void prioritiesChangeTheOrderWorkIsHandedOut() throws Exception {
        order("SO-1", 5, 2);
        order("SO-2", 5, 2);
        String second = id("SO-2");
        prioritize(1, second).andExpect(jsonPath("$.changed").value(1));
        call(get("/api/tasks")).andExpect(jsonPath("$.content[0].id").value(second)).andExpect(jsonPath("$.content[0].priority").value(1))
                .andExpect(jsonPath("$.content[0].ownPriority").value(5)).andExpect(jsonPath("$.content[0].prioritized").value(true));
        call(json(post("/api/rf/picking/start"), Map.of("lpnId", "T1")).cookie(ann)).andExpect(jsonPath("$.task.id").value(second));
        // Back to what it was.
        prioritize(null, id("COUNT"), id("PUTAWAY")).andExpect(jsonPath("$.changed").value(2));
        prioritize(0, id("COUNT")).andExpect(status().isBadRequest());
        prioritize(10, id("COUNT")).andExpect(status().isBadRequest());
        prioritize(1).andExpect(status().isBadRequest());
        prioritize(1, UUID.randomUUID().toString()).andExpect(status().isConflict());
    }

    @Test
    void kindsOfWorkHaveTheirOwnPriorities() throws Exception {
        call(get("/api/tasks/settings")).andExpect(jsonPath("$", hasSize(5))).andExpect(jsonPath("$[2].kind").value("COUNT"))
                .andExpect(jsonPath("$[2].priority").value(7)).andExpect(jsonPath("$[3].priority").doesNotExist())
                .andExpect(jsonPath("$[4].canBeOffered").value(false));
        call(json(put("/api/tasks/settings"), List.of(Map.of("kind", "COUNT", "priority", 1, "offered", true),
                Map.of("kind", "REPLENISHMENT", "priority", 1, "offered", false), Map.of("kind", "PICK", "priority", 4, "offered", true),
                Map.of("kind", "PACK", "offered", true))))
                .andExpect(status().isOk()).andExpect(jsonPath("$[3].priority").doesNotExist()).andExpect(jsonPath("$[4].offered").value(false));
        assertThat(queue(Map.of())).containsExactlyInAnyOrder("REPLENISHMENT 1", "COUNT 1", "PUTAWAY 5").last().isEqualTo("PUTAWAY 5");
        // Replenishment isn't offered any more.
        next(ann).andExpect(jsonPath("$.offer.kind").value("COUNT")).andExpect(jsonPath("$.waiting").value(2));
        call(json(put("/api/tasks/settings"), List.of(Map.of("kind", "COUNT", "offered", true)))).andExpect(status().isBadRequest());
    }

    @Test
    void packingFollowsPrioritiesToo() throws Exception {
        order("SO-1", 5, 2);
        order("SO-2", 5, 2);
        pickAndDrop(ann, "T1");
        pickAndDrop(ann, "T2");
        JsonNode packs = body(call(get("/api/tasks").param("kind", "PACK"))).get("content");
        assertThat(packs).hasSize(2);
        String last = packs.get(1).get("id").asString();
        prioritize(2, last).andExpect(status().isOk());
        call(get("/api/rf/packing/stations/DC1-PACK-01").cookie(ann)).andExpect(jsonPath("$.tasks[0].id").value(last));
        assign("BOB", last).andExpect(status().isConflict()).andExpect(jsonPath("$.detail", containsString("DC1-PACK-01")));
    }

    // ---------- Assignment ----------

    @Test
    void assignedWorkIsForOnePerson() throws Exception {
        order("SO-1", 5, 2);
        assign("BOB", id("PUTAWAY"), id("REPLENISHMENT"), id("COUNT"), id("PICK")).andExpect(jsonPath("$.changed").value(4));
        call(get("/api/tasks")).andExpect(jsonPath("$.content[0].assignedTo.name").value("Bob Ray"));
        assertThat(queue(Map.of("state", "ASSIGNED"))).hasSize(4);
        assertThat(queue(Map.of("state", "UNASSIGNED"))).isEmpty();
        assertThat(queue(Map.of("person", people.idOf("BOB").toString()))).hasSize(4);
        call(get("/api/tasks/people")).andExpect(jsonPath("$[?(@.name == 'Bob Ray')].assigned").value(4));
        call(get("/api/users/" + people.idOf("BOB"))).andExpect(jsonPath("$.inHand", hasItem("4 tasks assigned")));

        // Ann isn't handed any of it.
        call(get("/api/rf/putaway").cookie(ann)).andExpect(jsonPath("$.waitingCount").value(0)).andExpect(jsonPath("$.waiting", hasSize(0)));
        call(get("/api/rf/replenishment").cookie(ann)).andExpect(jsonPath("$.waiting", hasSize(0)));
        call(get("/api/rf/counts").cookie(ann)).andExpect(jsonPath("$.waiting", hasSize(0)));
        call(json(post("/api/rf/replenishment/start"), Map.of()).cookie(ann)).andExpect(status().isConflict());
        call(json(post("/api/rf/replenishment/start"), Map.of("taskId", id("REPLENISHMENT"))).cookie(ann)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("assigned to Bob Ray")));
        call(json(post("/api/rf/picking/start"), Map.of("lpnId", "T1")).cookie(ann)).andExpect(status().isConflict());
        next(ann).andExpect(jsonPath("$.offer").doesNotExist()).andExpect(jsonPath("$.waiting").value(0));
        call(get("/api/rf/putaway").cookie(bob)).andExpect(jsonPath("$.waitingCount").value(1));
        // Scanning it all the same asks first.
        call(json(post("/api/rf/putaway/start"), Map.of("lpnId", "LP1")).cookie(ann)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors.assignedTo").value("Bob Ray"));
        call(json(post("/api/rf/counts/start"), Map.of("locationId", "BK-1")).cookie(ann)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors.assignedTo").value("Bob Ray"));
        call(json(post("/api/rf/putaway/start"), Map.of("lpnId", "LP1", "takeOver", true)).cookie(ann)).andExpect(status().isOk());
        assign("BOB", id("PUTAWAY")).andExpect(status().isConflict()).andExpect(jsonPath("$.detail", containsString("Ann Lee is already doing")));
        // Bob gets on with his.
        call(json(post("/api/rf/picking/start"), Map.of("lpnId", "T1")).cookie(bob)).andExpect(status().isOk());

        // Nobody in particular again.
        assign(null, id("COUNT")).andExpect(status().isOk());
        call(get("/api/rf/counts").cookie(ann)).andExpect(jsonPath("$.waiting", hasSize(1)));
    }

    @Test
    void workIsOnlyAssignedToThoseWhoMayDoIt() throws Exception {
        var ed = people.operator("ED", "Ed Poe", PIN);
        ed.setStatus(ActiveStatus.INACTIVE);
        assign("ED", id("COUNT")).andExpect(status().isBadRequest());
        users.findByUserCode("ED").orElseThrow().setStatus(ActiveStatus.ACTIVE);
        assign("ED", id("COUNT")).andExpect(status().isOk());
        call(get("/api/rf/counts").cookie(ann)).andExpect(jsonPath("$.waiting", hasSize(0)));
        // Someone who can't sign in any more doesn't hold it up.
        users.findByUserCode("ED").orElseThrow().setStatus(ActiveStatus.INACTIVE);
        call(get("/api/rf/counts").cookie(ann)).andExpect(jsonPath("$.waiting", hasSize(1)));
        call(get("/api/tasks").param("kind", "COUNT")).andExpect(jsonPath("$.content[0].assignedTo").doesNotExist());
        // Someone without the permission.
        String role = body(call(json(post("/api/roles"), Map.of("name", "Counter", "description", "", "permissions", List.of("DEVICE_COUNT", "DEVICE_NEXT"))))
                .andExpect(status().isCreated())).get("id").asString();
        String cy = body(call(json(post("/api/users"), Map.of("userCode", "CY", "displayName", "Cy Day", "status", "ACTIVE", "pin", PIN, "roleIds", List.of(role))))
                .andExpect(status().isCreated())).get("id").asString();
        assertThat(cy).isNotNull();
        assign("CY", id("PUTAWAY")).andExpect(status().isConflict()).andExpect(jsonPath("$.errors.userId").exists());
        assign("CY", id("COUNT"), id("PUTAWAY")).andExpect(status().isConflict());
        // And they are only offered what they may do.
        next(signIn("CY")).andExpect(jsonPath("$.offer.kind").value("COUNT")).andExpect(jsonPath("$.waiting").value(1));
    }

    // ---------- What to do next ----------

    @Test
    void theMostUrgentTaskIsOfferedToOnePerson() throws Exception {
        next(ann).andExpect(jsonPath("$.offer.kind").value("REPLENISHMENT")).andExpect(jsonPath("$.waiting").value(3));
        next(bob).andExpect(jsonPath("$.offer.kind").value("PUTAWAY"));
        next(ann).andExpect(jsonPath("$.offer.kind").value("REPLENISHMENT"));
        // Something else, please.
        String skipped = id("REPLENISHMENT");
        next(ann, skipped).andExpect(jsonPath("$.offer.kind").value("COUNT"));
        next(bob, id("PUTAWAY")).andExpect(jsonPath("$.offer.kind").value("REPLENISHMENT"));
        next(ann, skipped, id("COUNT"), id("PUTAWAY")).andExpect(jsonPath("$.offer").doesNotExist()).andExpect(jsonPath("$.waiting").value(2));
        // What one was told to do comes before anything more urgent.
        assign("ANN", id("COUNT")).andExpect(status().isOk());
        next(ann).andExpect(jsonPath("$.offer.kind").value("COUNT")).andExpect(jsonPath("$.offer.assignedTo.name").value("Ann Lee"));
    }

    @Test
    void startingSomethingElseLeavesWhatWasOfferedToOthers() throws Exception {
        next(ann).andExpect(jsonPath("$.offer.kind").value("REPLENISHMENT"));
        next(bob).andExpect(jsonPath("$.offer.kind").value("PUTAWAY"));
        call(json(post("/api/rf/counts/start"), Map.of("locationId", "BK-1")).cookie(ann)).andExpect(status().isOk());
        next(bob).andExpect(jsonPath("$.offer.kind").value("REPLENISHMENT"));
    }

    @Test
    void whatIsStartedIsFinishedFirst() throws Exception {
        next(ann);
        call(json(post("/api/rf/replenishment/start"), Map.of("taskId", id("REPLENISHMENT"))).cookie(ann)).andExpect(status().isOk());
        next(ann).andExpect(jsonPath("$.doing", hasSize(1))).andExpect(jsonPath("$.doing[0].kind").value("REPLENISHMENT"))
                .andExpect(jsonPath("$.offer").doesNotExist());
    }

    @Test
    void ofEquallyUrgentTasksTheNearestIsOffered() throws Exception {
        // Two counts, equally urgent; the one planned first is far off.
        call(json(post("/api/inventory/counts"), Map.of("locationId", "PICK-1"))).andExpect(status().isOk());
        prioritize(9, id("REPLENISHMENT"), id("PUTAWAY"));
        next(ann).andExpect(jsonPath("$.offer.location").value("BK-1"));
        // Ann puts something away at PICK-1, so that is where she is.
        call(json(post("/api/rf/putaway/start"), Map.of("lpnId", "LP1")).cookie(ann)).andExpect(status().isOk());
        call(json(post("/api/rf/putaway/tasks/" + id("PUTAWAY") + "/confirm"), Map.of("locationId", "PICK-1", "reason", "OTHER", "handling", "EMPTY_LPN",
                "allowOver", true)).cookie(ann)).andExpect(status().isOk());
        next(ann).andExpect(jsonPath("$.offer.location").value("PICK-1"));
        // Unless another is more urgent.
        for (JsonNode t : body(call(get("/api/tasks").param("kind", "COUNT"))).get("content")) {
            if (t.get("location").asString().equals("BK-1")) {
                prioritize(2, t.get("id").asString());
            }
        }
        next(ann).andExpect(jsonPath("$.offer.location").value("BK-1"));
    }

    @Test
    void anOfferedPickIsStartedAsItIs() throws Exception {
        order("SO-1", 5, 2);
        order("SO-2", 1, 2);
        String first = id("SO-1");
        call(json(post("/api/rf/picking/start"), Map.of("lpnId", "T1", "taskId", first)).cookie(ann)).andExpect(jsonPath("$.task.id").value(first));
        call(json(post("/api/rf/picking/start"), Map.of("lpnId", "T2", "taskId", first)).cookie(bob)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("isn't waiting")));
    }

    @Test
    void whatCantBeDoneNowIsNotOffered() throws Exception {
        order("SO-1", 1, 60); // from RS-1 and BK-1
        call(json(post("/api/rf/counts/start"), Map.of("locationId", "BK-1")).cookie(bob)).andExpect(status().isOk());
        call(get("/api/tasks").param("kind", "PICK")).andExpect(jsonPath("$.content[0].ready").value(false));
        next(ann).andExpect(jsonPath("$.offer.kind").value("REPLENISHMENT")).andExpect(jsonPath("$.waiting").value(2));
    }

    // ---------- Helpers ----------

    private List<String> queue(Map<String, String> params) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/tasks");
        params.forEach(request::param);
        List<String> result = new ArrayList<>();
        body(call(request)).get("content").forEach(t -> result.add(t.get("kind").asString() + " " + t.get("priority").asInt()));
        return result;
    }

    /** The task of a kind, or whose description says something. */
    private String id(String what) throws Exception {
        JsonNode all = body(call(get("/api/tasks"))).get("content");
        for (JsonNode t : all) {
            if (t.get("kind").asString().equals(what)) {
                return t.get("id").asString();
            }
        }
        for (JsonNode t : all) {
            if (t.get("what").asString().contains(what)) {
                return t.get("id").asString();
            }
        }
        throw new AssertionError("no task " + what);
    }

    private ResultActions prioritize(Integer priority, String... ids) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("ids", List.of(ids)));
        body.put("priority", priority);
        return call(json(post("/api/tasks/priority"), body));
    }

    private ResultActions assign(String user, String... ids) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("ids", List.of(ids)));
        body.put("userId", user == null ? null : people.idOf(user));
        return call(json(post("/api/tasks/assign"), body));
    }

    private ResultActions next(Cookie who, String... skip) throws Exception {
        return call(json(post("/api/rf/work/next"), Map.of("skip", List.of(skip))).cookie(who)).andExpect(status().isOk());
    }

    private void order(String number, int priority, int quantity) throws Exception {
        call(json(post("/api/orders"), Map.of("orderNumber", number, "customerName", "Cafe", "priority", priority,
                "lines", List.of(Map.of("itemId", "SKU-CUP", "quantity", quantity))))).andExpect(status().isCreated());
    }

    private void pickAndDrop(Cookie who, String tote) throws Exception {
        JsonNode pick = body(call(json(post("/api/rf/picking/start"), Map.of("lpnId", tote)).cookie(who)).andExpect(status().isOk())).get("pick");
        Map<String, Object> confirm = new HashMap<>(Map.of("detailId", pick.get("detailId").asString(), "pickedSoFar", 0, "locationId",
                pick.get("locationId").asString(), "itemBarcode", "333000", "quantity", pick.get("remaining").asInt(), "shortPick", false));
        call(json(post("/api/rf/picking/confirm"), confirm).cookie(who)).andExpect(jsonPath("$.step").value("DROP"));
        call(json(post("/api/rf/picking/drop"), Map.of("locationId", "DC1-PACK-01")).cookie(who)).andExpect(status().isOk());
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

    private void stock(int quantity, int order, Consumer<InventoryRecord> place) {
        InventoryRecord r = new InventoryRecord(UUID.randomUUID(), cups, quantity);
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

    private Location location(String code, LocationType type, String zone, String aisle) {
        Location l = new Location(UUID.randomUUID());
        l.setLocationId(code);
        l.setType(type);
        l.setArea("DC1");
        l.setZone(zone);
        if (type == LocationType.STORAGE) {
            l.setAisle(aisle);
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
