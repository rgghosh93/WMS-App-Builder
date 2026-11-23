package com.wms.masterdata.zone;

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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.flywaydb.core.api.configuration.Configuration;
import org.flywaydb.core.api.migration.Context;
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
import com.wms.outbound.OrderRequest;
import com.wms.outbound.OrderService;
import com.wms.users.TestUsers;

import db.migration.V26__putaway_zones_for_rules;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ZoneTest {

    private static final String PIN = "8264";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private EntityManager em;
    @Autowired private ItemRepository items;
    @Autowired private LocationRepository locations;
    @Autowired private InventoryRepository inventory;
    @Autowired private ContainerRepository containers;
    @Autowired private ContainerTypeRepository types;
    @Autowired private TestUsers people;
    @Autowired private OrderService orderService;

    private Item cups;
    private Cookie ann;

    /** Cups, oldest first: 10 at A1 (aisle 01), 10 at A2 (aisle 02), 10 at A3 (aisle 03). */
    @BeforeEach
    void fixtures() throws Exception {
        cups = item("SKU-CUP", "333000");
        for (int i = 1; i <= 3; i++) {
            stock(cups, location("A" + i, LocationType.STORAGE, "BK", "0" + i), null, i);
        }
        location("A4", LocationType.STORAGE, "BK", "04");
        location("RS1", LocationType.STORAGE, "RS", "01");
        location("DC1-PACK-01", LocationType.PACKING, "PACK", null);
        ContainerType tote = type("TOTE");
        for (String lpn : List.of("T1", "T2")) {
            Container c = new Container(UUID.randomUUID());
            c.setLpnId(lpn);
            c.setContainerType(tote);
            c.setStatus(ContainerStatus.AVAILABLE);
            c.setPermanent(true);
            containers.save(c);
        }
        people.operator("ANN", "Ann Lee", PIN);
        ann = signIn("ANN");
    }

    // ---------- Zones and their locations ----------

    @Test
    void zonesAreKeptByFunction() throws Exception {
        String id = zone("PICKING", "fast", Map.of());
        call(get("/api/zones/" + id)).andExpect(jsonPath("$.code").value("FAST")).andExpect(jsonPath("$.locationCount").value(0));
        // The same code is fine for another function, not for the same one.
        zone("ALLOCATION", "FAST", Map.of("priority", 5, "allocatable", false));
        save(null, "PICKING", "FAST", Map.of()).andExpect(status().isConflict()).andExpect(jsonPath("$.errors.code").exists());
        call(get("/api/zones").param("function", "ALLOCATION")).andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].priority").value(5)).andExpect(jsonPath("$[0].allocatable").value(false));
        // Priority and whether orders use it are only for allocation.
        save(id, "PICKING", "FAST", Map.of("priority", 5, "allocatable", false, "version", 0, "name", "Fast lane")).andExpect(status().isOk())
                .andExpect(jsonPath("$.priority").doesNotExist()).andExpect(jsonPath("$.allocatable").value(true))
                .andExpect(jsonPath("$.name").value("Fast lane"));
        save(id, "PICKING", "FAST", Map.of("version", 0)).andExpect(status().isConflict());
        save(id, "PICKING", "FAST", Map.of()).andExpect(status().isBadRequest());
        save(id, "COUNTING", "FAST", Map.of("version", 1)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.function").exists());
        save(null, "PICKING", "no spaces", Map.of()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.code").exists());
        save(null, "ALLOCATION", "P", Map.of("priority", 0)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.priority").exists());
        add(id, Map.of("area", "DC1"), false).andExpect(status().isOk());
        call(delete("/api/zones/" + id)).andExpect(status().isNoContent());
        call(get("/api/zones/" + id)).andExpect(status().isNotFound());
        call(get("/api/zones/functions")).andExpect(jsonPath("$", hasSize(5)));
    }

    @Test
    void locationsAreAddedByAddressOrById() throws Exception {
        String id = zone("PICKING", "Z1", Map.of());
        preview(id, Map.of("area", "dc1", "zone", "bk", "aisleFrom", "02", "aisleTo", "03")).andExpect(jsonPath("$.matching").value(2));
        add(id, Map.of("area", "DC1", "zone", "BK", "aisleFrom", "02", "aisleTo", "03"), false).andExpect(status().isOk());
        preview(id, Map.of("area", "DC1")).andExpect(jsonPath("$.matching").value(5)).andExpect(jsonPath("$.already").value(2));
        preview(id, Map.of("area", "DC1", "zone", "BK", "bayFrom", "02")).andExpect(jsonPath("$.matching").value(0));
        preview(id, Map.of("area", "DC1", "aisleTo", "01", "level", "a")).andExpect(jsonPath("$.matching").value(2));
        add(id, Map.of("locationIds", List.of("rs1", "RS1", "A2")), false).andExpect(status().isOk());
        call(get("/api/zones/" + id + "/locations")).andExpect(jsonPath("$.content[0].locationId").value("A2"))
                .andExpect(jsonPath("$.content[1].locationId").value("A3")).andExpect(jsonPath("$.content[2].locationId").value("RS1"))
                .andExpect(jsonPath("$.totalElements").value(3));
        call(get("/api/zones/" + id + "/locations").param("q", "a3")).andExpect(jsonPath("$.totalElements").value(1));
        call(get("/api/zones/" + id + "/locations").param("q", "%")).andExpect(jsonPath("$.totalElements").value(0));
        call(get("/api/zones").param("function", "PICKING")).andExpect(jsonPath("$[0].locationCount").value(3));

        add(id, Map.of("locationIds", List.of("DC1-PACK-01")), false).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.locationIds", containsString("not for storage")));
        add(id, Map.of("locationIds", List.of("NOPE")), false).andExpect(status().isBadRequest());
        add(id, Map.of("area", "DC9"), false).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.filter").exists());
        add(id, Map.of(), false).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.area").exists());

        call(json(post("/api/zones/" + id + "/locations/remove"), Map.of("locationIds", List.of(locationId("A3")))))
                .andExpect(jsonPath("$.locationCount").value(2));
        call(json(post("/api/zones/" + id + "/locations/remove"), Map.of())).andExpect(status().isBadRequest());
    }

    @Test
    void aLocationIsInOneZoneOfEachFunction() throws Exception {
        String one = zone("PICKING", "Z1", Map.of());
        String two = zone("PICKING", "Z2", Map.of());
        String counting = zone("COUNTING", "Z1", Map.of());
        add(one, Map.of("area", "DC1"), false).andExpect(status().isOk());
        add(counting, Map.of("area", "DC1"), false).andExpect(status().isOk());
        add(two, Map.of("locationIds", List.of("A1")), false).andExpect(status().isConflict()).andExpect(jsonPath("$.errors.elsewhere").value("1 in Z1"));
        add(two, Map.of("locationIds", List.of("A1")), true).andExpect(status().isOk());
        call(get("/api/locations/" + locationId("A1") + "/zones")).andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[?(@.function == 'PICKING')].code").value("Z2"))
                .andExpect(jsonPath("$[?(@.function == 'COUNTING')].code").value("Z1"));
        call(get("/api/zones/" + one)).andExpect(jsonPath("$.locationCount").value(4));
        // Deleting a location takes it out of its zones.
        call(delete("/api/locations/" + locationId("A4"))).andExpect(status().isNoContent());
        call(get("/api/zones/" + one)).andExpect(jsonPath("$.locationCount").value(3));
    }

    // ---------- Allocation ----------

    @Test
    void allocationTakesFromZonesByPriorityAndSkipsThoseKeptFromOrders() throws Exception {
        assertThat(takes("SO-0", 10)).containsExactly("A1");
        String first = zone("ALLOCATION", "FIRST", Map.of("priority", 1));
        add(first, Map.of("locationIds", List.of("A3")), false);
        assertThat(takes("SO-1", 4)).containsExactly("A3");
        String kept = zone("ALLOCATION", "KEPT", Map.of("allocatable", false));
        add(kept, Map.of("locationIds", List.of("A2")), false);
        onHand().andExpect(jsonPath("$.content[0].total.available").value(6)).andExpect(jsonPath("$.content[0].total.elsewhere").value(10));
        JsonNode order = order("SO-2", 10);
        assertThat(order.get("status").asString()).isEqualTo("PARTIALLY_ALLOCATED");
        assertThat(from(order)).containsExactly("A3");
        // Not once the zone is inactive.
        save(kept, "ALLOCATION", "KEPT", Map.of("allocatable", false, "version", 0, "status", "INACTIVE")).andExpect(status().isOk());
        onHand().andExpect(jsonPath("$.content[0].total.available").value(10));
        assertThat(takes("SO-3", 4)).containsExactly("A2");
    }

    @Test
    void earliestExpiryGoesFirstWhateverTheZone() throws Exception {
        Item milk = item("SKU-MILK", "444000");
        stock(milk, locations.findByLocationId("A1").orElseThrow(), LocalDate.now().plusDays(90), 1);
        stock(milk, locations.findByLocationId("A2").orElseThrow(), LocalDate.now().plusDays(30), 2);
        stock(milk, locations.findByLocationId("A3").orElseThrow(), LocalDate.now().plusDays(90), 3);
        add(zone("ALLOCATION", "FIRST", Map.of("priority", 1)), Map.of("locationIds", List.of("A3")), false);
        JsonNode order = body(call(json(post("/api/orders"), Map.of("orderNumber", "SO-M", "customerName", "Cafe",
                "lines", List.of(Map.of("itemId", "SKU-MILK", "quantity", 25))))).andExpect(status().isCreated()));
        Map<String, Integer> taken = new HashMap<>();
        order.get("allocations").forEach(a -> taken.put(a.get("location").get("locationId").asString(), a.get("quantity").asInt()));
        // All of the one that expires first, then all of the zone's, then the rest.
        assertThat(taken).isEqualTo(Map.of("A2", 10, "A3", 10, "A1", 5));
    }

    // ---------- Picking ----------

    @Test
    void picksAreSplitByPickZone() throws Exception {
        String east = zone("PICKING", "EAST", Map.of());
        String west = zone("PICKING", "WEST", Map.of());
        add(east, Map.of("locationIds", List.of("A1")), false);
        add(west, Map.of("locationIds", List.of("A2")), false);
        JsonNode order = order("SO-1", 25);
        assertThat(order.get("status").asString()).isEqualTo("ALLOCATED");
        List<String> zones = new ArrayList<>();
        order.get("tasks").forEach(t -> {
            zones.add(t.get("pickZone").isNull() ? "-" : t.get("pickZone").asString());
            assertThat(t.get("detailCount").asInt()).isEqualTo(1);
        });
        assertThat(zones).containsExactlyInAnyOrder("EAST", "WEST", "-");
        call(get("/api/pick-tasks").param("pickZone", "east")).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].pickZone").value("EAST"));
        call(get("/api/rf/picking/zones").cookie(ann)).andExpect(jsonPath("$", hasSize(2))).andExpect(jsonPath("$[0].code").value("EAST"))
                .andExpect(jsonPath("$[0].waiting").value(1));

        // A picker in a zone gets only its tasks.
        call(json(post("/api/rf/picking/start"), Map.of("lpnId", "T1", "zoneId", west)).cookie(ann)).andExpect(status().isOk())
                .andExpect(jsonPath("$.task.pickZone").value("WEST"));
        call(get("/api/rf/picking/zones").cookie(ann)).andExpect(jsonPath("$[1].waiting").value(0));
        people.operator("BOB", "Bob Ray", PIN);
        Cookie bob = signIn("BOB");
        call(json(post("/api/rf/picking/start"), Map.of("lpnId", "T2", "zoneId", west)).cookie(bob)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("No pick tasks are waiting in that zone."));
        // Without one, any task.
        call(json(post("/api/rf/picking/start"), Map.of("lpnId", "T2")).cookie(bob)).andExpect(status().isOk());

        // Deleting the zone leaves its tasks.
        call(delete("/api/zones/" + west)).andExpect(status().isNoContent());
        call(get("/api/rf/picking").cookie(ann)).andExpect(jsonPath("$.step").value("PICK")).andExpect(jsonPath("$.task.pickZone").doesNotExist());
    }

    @Test
    void aBatchIsSplitByPickZoneToo() throws Exception {
        String east = zone("PICKING", "EAST", Map.of());
        add(east, Map.of("locationIds", List.of("A1")), false);
        String one = received("SO-1", 8);
        String two = received("SO-2", 8);
        JsonNode result = body(call(json(post("/api/orders/batch-allocate"), Map.of("orderIds", List.of(one, two)))));
        assertThat(result.get("tasks")).hasSize(2);
        JsonNode none = result.get("tasks").get(0);
        JsonNode zoned = result.get("tasks").get(1);
        assertThat(none.get("pickZone").isNull()).isTrue();
        assertThat(zoned.get("pickZone").asString()).isEqualTo("EAST");
        assertThat(zoned.get("orders")).hasSize(2);
        assertThat(zoned.get("totalQuantity").asInt()).isEqualTo(10);
        assertThat(none.get("totalQuantity").asInt()).isEqualTo(6);
        assertThat(result.get("task").get("id")).isEqualTo(none.get("id"));
    }

    @Test
    void withoutPickZonesThereIsOneTask() throws Exception {
        assertThat(order("SO-1", 25).get("tasks")).hasSize(1);
        call(get("/api/rf/picking/zones").cookie(ann)).andExpect(jsonPath("$", hasSize(0)));
        // An inactive zone is as if it weren't there.
        String east = zone("PICKING", "EAST", Map.of("status", "INACTIVE"));
        add(east, Map.of("locationIds", List.of("A3")), false);
        assertThat(order("SO-2", 3).get("tasks").get(0).get("pickZone").isNull()).isTrue();
    }

    // ---------- Counting and put-away ----------

    @Test
    void aCountingZoneIsCounted() throws Exception {
        String id = zone("COUNTING", "WEEKLY", Map.of());
        call(json(post("/api/inventory/counts"), Map.of("zoneId", id))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.zoneId").exists());
        add(id, Map.of("locationIds", List.of("A1", "A4")), false);
        call(json(post("/api/inventory/counts"), Map.of("zoneId", id))).andExpect(jsonPath("$.created").value(1));
        call(json(post("/api/inventory/counts"), Map.of("zoneId", id, "includeEmpty", true))).andExpect(jsonPath("$.created").value(1));
        String other = zone("PICKING", "EAST", Map.of());
        call(json(post("/api/inventory/counts"), Map.of("zoneId", other))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.zoneId", containsString("counting")));
    }

    @Test
    void aZoneAPutAwayRuleUsesStays() throws Exception {
        String id = zone("PUTAWAY", "RESERVE", Map.of());
        JsonNode rule = body(call(json(post("/api/putaway/rules"), Map.of("sequence", 5, "name", "To reserve", "status", "ACTIVE", "strategy", "ZONE",
                "zoneId", id, "handling", "KEEP_LPN"))).andExpect(status().isCreated()));
        assertThat(rule.get("zone").get("code").asString()).isEqualTo("RESERVE");
        call(delete("/api/zones/" + id)).andExpect(status().isConflict()).andExpect(jsonPath("$.detail", containsString("put-away rule")));
        call(delete("/api/putaway/rules/" + rule.get("id").asString())).andExpect(status().isNoContent());
        call(delete("/api/zones/" + id)).andExpect(status().isNoContent());
    }

    /** Rules that named an address zone get a put-away zone of the same locations. */
    @Test
    void oldPutAwayRulesAreGivenZones() throws Exception {
        try (Connection db = DriverManager.getConnection("jdbc:h2:mem:zones;MODE=PostgreSQL"); Statement s = db.createStatement()) {
            s.execute("CREATE TABLE zones (id UUID, zone_function VARCHAR(16), code VARCHAR(40), name VARCHAR(120), status VARCHAR(10), "
                    + "allocatable BOOLEAN, version BIGINT)");
            s.execute("CREATE TABLE zone_locations (id UUID, zone_id UUID, zone_function VARCHAR(16), location_id UUID)");
            s.execute("CREATE TABLE locations (id UUID, location_type VARCHAR(16), area_code VARCHAR(20), zone_code VARCHAR(20))");
            s.execute("CREATE TABLE putaway_rules (name VARCHAR(20), area_code VARCHAR(20), zone_code VARCHAR(20), zone_id UUID)");
            s.execute("INSERT INTO locations VALUES (random_uuid(), 'STORAGE', 'DC1', 'RS'), (random_uuid(), 'STORAGE', 'DC1', 'RS'), "
                    + "(random_uuid(), 'STORAGE', 'DC1', 'BK'), (random_uuid(), 'RECEIVING', 'DC1', 'RS')");
            s.execute("INSERT INTO putaway_rules VALUES ('A', 'DC1', 'RS', NULL), ('B', 'DC1', 'RS', NULL), ('C', 'DC1', 'BK', NULL), "
                    + "('D', NULL, NULL, NULL)");
            new V26__putaway_zones_for_rules().migrate(new Context() {
                @Override
                public Configuration getConfiguration() {
                    return null;
                }

                @Override
                public Connection getConnection() {
                    return db;
                }
            });
            ResultSet rs = s.executeQuery("SELECT r.name, z.code, (SELECT COUNT(*) FROM zone_locations l WHERE l.zone_id = z.id) "
                    + "FROM putaway_rules r LEFT JOIN zones z ON z.id = r.zone_id ORDER BY r.name");
            List<String> rows = new ArrayList<>();
            while (rs.next()) {
                rows.add(rs.getString(1) + " " + rs.getString(2) + " " + rs.getInt(3));
            }
            assertThat(rows).containsExactly("A DC1-RS 2", "B DC1-RS 2", "C DC1-BK 1", "D null 0");
        }
    }

    // ---------- Helpers ----------

    private String zone(String function, String code, Map<String, Object> more) throws Exception {
        return body(save(null, function, code, more).andExpect(status().isCreated())).get("id").asString();
    }

    private ResultActions save(String id, String function, String code, Map<String, Object> more) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("function", function, "code", code, "name", code, "status", "ACTIVE"));
        body.putAll(more);
        return call(json(id == null ? post("/api/zones") : put("/api/zones/" + id), body));
    }

    private ResultActions preview(String id, Map<String, Object> filter) throws Exception {
        return call(json(post("/api/zones/" + id + "/locations/preview"), filter)).andExpect(status().isOk());
    }

    private ResultActions add(String id, Map<String, Object> filter, boolean move) throws Exception {
        return call(json(post("/api/zones/" + id + "/locations"), Map.of("filter", filter, "move", move)));
    }

    private ResultActions onHand() throws Exception {
        return call(get("/api/inventory/on-hand").param("q", "SKU-CUP"));
    }

    private JsonNode order(String number, int quantity) throws Exception {
        return body(call(json(post("/api/orders"), Map.of("orderNumber", number, "customerName", "Cafe",
                "lines", List.of(Map.of("itemId", "SKU-CUP", "quantity", quantity))))).andExpect(status().isCreated()));
    }

    /** An order not allocated yet. */
    private String received(String number, int quantity) {
        return orderService.create(new OrderRequest(number, "Cafe", null, 5, List.of(new OrderRequest.Line("SKU-CUP", quantity, null))), false)
                .id().toString();
    }

    private List<String> takes(String number, int quantity) throws Exception {
        return from(order(number, quantity));
    }

    private static List<String> from(JsonNode order) {
        List<String> result = new ArrayList<>();
        order.get("allocations").forEach(a -> result.add(a.get("location").get("locationId").asString()));
        return result;
    }

    private String locationId(String code) {
        return locations.findByLocationId(code).orElseThrow().getId().toString();
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

    private void stock(Item item, Location location, LocalDate expiry, int order) {
        InventoryRecord r = new InventoryRecord(UUID.randomUUID(), item, 10);
        r.setExpiryDate(expiry);
        r.setReceivedAt(Instant.parse("2026-01-0" + order + "T00:00:00Z"));
        r.placeIn(location);
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
        l.setPosition("01");
        l.setWidth(BigDecimal.TEN);
        l.setDepth(BigDecimal.TEN);
        l.setHeight(BigDecimal.TEN);
        l.setDimensionUom(DimensionUom.IN);
        return locations.save(l);
    }

    private ContainerType type(String code) {
        ContainerType t = new ContainerType(UUID.randomUUID());
        t.setContainerTypeId(code);
        t.setDescription(code);
        t.setCategory(ContainerCategory.TOTE);
        t.setStatus(ActiveStatus.ACTIVE);
        t.setReusable(true);
        t.setWidth(BigDecimal.ONE);
        t.setDepth(BigDecimal.ONE);
        t.setHeight(BigDecimal.ONE);
        t.setDimensionUom(DimensionUom.IN);
        return types.save(t);
    }
}
