package com.wms.facility;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Two facilities and two owners side by side, over HTTP and with every step committed, in a database of its own: what
 * is in one facility is nowhere to be seen in the other, and what is one owner's never goes to the other.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:wms-facility;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"})
class FacilityTest {

    private static final String PIN = "482913";
    private static final String PASSWORD = "correct horse battery";
    private static final String FOUND = "00000000-0000-4000-8001-000000000001";
    private static final String RECALL = "00000000-0000-4000-8001-000000000007";
    private static final String MAIN = Site.FIRST.toString();

    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate db;
    @Value("${local.server.port}") private int port;

    /** Someone with a sign-in of their own. */
    private final class Person {
        private final HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();

        Answer get(String path) throws Exception {
            return send(request(path).GET());
        }

        Answer post(String path, Object body) throws Exception {
            return send(request(path).POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))));
        }

        Answer put(String path, Object body) throws Exception {
            return send(request(path).PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))));
        }

        Answer delete(String path) throws Exception {
            return send(request(path).DELETE());
        }

        private Answer send(HttpRequest.Builder request) throws Exception {
            HttpResponse<String> r = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            return new Answer(r.request().method() + " " + r.uri().getPath(), r.statusCode(), r.body().isEmpty() ? null : mapper.readTree(r.body()));
        }
    }

    private record Answer(String what, int status, JsonNode body) {
        JsonNode ok() {
            assertThat(status).as(what + " -> " + body).isBetween(200, 299);
            return body;
        }

        Answer is(int expected) {
            assertThat(status).as(what + " -> " + body).isEqualTo(expected);
            return this;
        }
    }

    private final Person boss = new Person();
    private final Person device = new Person();
    private String east;

    /** A second facility, EAST, with a person EVE who only works there, and a second owner, ACME. Boss is in MAIN. */
    @BeforeEach
    void signIn() throws Exception {
        boolean first = boss.get("/api/session").ok().get("state").asString().equals("SETUP");
        boss.post(first ? "/api/session/setup" : "/api/session", Map.of("userCode", "BOSS", "displayName", "The Boss", "password", PASSWORD)).ok();
        if (first) {
            east = boss.post("/api/facilities", Map.of("code", "east", "name", "East side", "status", "ACTIVE")).ok().get("id").asString();
            boss.post("/api/owners", Map.of("code", "acme", "name", "Acme Foods", "status", "ACTIVE")).ok();
            user("ANN", List.of());
            user("EVE", List.of(east));
            boss.post("/api/uoms", Map.of("uomCode", "CS", "description", "Case", "kind", "CASE", "status", "ACTIVE")).ok();
            for (String[] t : new String[][] {{"PLT", "PALLET"}, {"TOTE", "TOTE"}}) {
                boss.post("/api/container-types", Map.of("containerTypeId", t[0], "description", t[0], "category", t[1], "status", "ACTIVE",
                        "reusable", t[1].equals("TOTE"), "width", 10, "depth", 10, "height", 10, "dimensionUom", "IN")).ok();
            }
            item("CUP", null, "111");
            item("CUP", "ACME", "111");
            for (String where : List.of(MAIN, east)) {
                go(boss, where);
                location("BK-1", "STORAGE");
                location("BK-2", "STORAGE");
                location("RCV-1", "RECEIVING");
                location("PACK-1", "PACKING");
            }
        }
        for (JsonNode f : boss.get("/api/facilities").ok()) {
            if (f.get("code").asString().equals("EAST")) {
                east = f.get("id").asString();
            }
        }
        go(boss, MAIN);
    }

    // ---------- Facilities ----------

    @Test
    void whatIsInOneFacilityIsNowhereInTheOther() throws Exception {
        String lpnType = boss.get("/api/container-types?q=TOTE").ok().get("content").get(0).get("id").asString();
        Map<String, String> there = new HashMap<>();
        there.put("/api/locations/", boss.get("/api/locations?q=BK-1").ok().get("content").get(0).get("id").asString());
        there.put("/api/containers/", boss.post("/api/containers", Map.of("lpnId", "LP-SAME", "containerTypeId", lpnType, "status", "AVAILABLE")).ok()
                .get("id").asString());
        there.put("/api/zones/", boss.post("/api/zones", Map.of("function", "PICKING", "code", "SAME", "name", "Same", "status", "ACTIVE")).ok().get("id").asString());
        there.put("/api/asns/", boss.post("/api/asns", Map.of("asnNumber", "IN-SAME", "vendorName", "V", "lines", List.of(Map.of("itemId", "CUP", "quantity", 1)))).ok()
                .get("id").asString());
        add("BK-1", null, 10).ok();
        JsonNode order = boss.post("/api/orders", order("OUT-SAME", null, 2)).ok();
        assertThat(order.get("status").asString()).isEqualTo("ALLOCATED");
        there.put("/api/orders/", order.get("id").asString());
        there.put("/api/pick-tasks/", order.get("tasks").get(0).get("id").asString());
        boss.post("/api/inventory/counts", Map.of("locationId", "BK-2", "includeEmpty", true)).ok();
        there.put("/api/inventory/counts/", boss.get("/api/inventory/counts?q=BK-2").ok().get("content").get(0).get("id").asString());
        there.put("/api/putaway/rules/", boss.post("/api/putaway/rules", Map.of("sequence", 1, "name", "Same", "status", "ACTIVE", "strategy", "NEAREST_EMPTY",
                "handling", "KEEP_LPN")).ok().get("id").asString());
        there.put("/api/replenishment/rules/", boss.post("/api/replenishment/rules", Map.of("itemId", order.get("lines").get(0).get("item").get("id").asString(),
                "locationId", boss.get("/api/locations?q=BK-2").ok().get("content").get(0).get("id").asString(), "minQuantity", 1, "maxQuantity", 2,
                "status", "ACTIVE")).ok().get("id").asString());

        go(boss, east);
        for (Map.Entry<String, String> e : there.entrySet()) {
            boss.get(e.getKey() + e.getValue()).is(404);
        }
        boss.post("/api/orders/" + there.get("/api/orders/") + "/cancel", Map.of()).is(404);
        boss.delete("/api/zones/" + there.get("/api/zones/")).is(404);
        boss.delete("/api/putaway/rules/" + there.get("/api/putaway/rules/")).is(404);
        for (String list : List.of("/api/containers", "/api/orders?q=OUT-SAME", "/api/asns?q=IN-SAME", "/api/inventory/stock", "/api/inventory/on-hand",
                "/api/pick-tasks", "/api/tasks", "/api/allocations", "/api/inventory/history?q=BK-1", "/api/inventory/counts", "/api/replenishment/tasks")) {
            assertThat(boss.get(list).ok().get("totalElements").asInt()).as(list).isZero();
        }
        assertThat(boss.get("/api/zones").ok()).isEmpty();
        assertThat(boss.get("/api/putaway/rules").ok()).isEmpty();
        assertThat(boss.get("/api/replenishment/rules").ok()).isEmpty();
        // The same names can be used again, for other things.
        boss.post("/api/containers", Map.of("lpnId", "LP-SAME", "containerTypeId", lpnType, "status", "AVAILABLE")).ok();
        boss.post("/api/zones", Map.of("function", "PICKING", "code", "SAME", "name", "Same", "status", "ACTIVE")).ok();
        boss.post("/api/asns", Map.of("asnNumber", "IN-SAME", "vendorName", "V", "lines", List.of(Map.of("itemId", "CUP", "quantity", 1)))).ok();
        // And there is nothing here to fill an order with.
        assertThat(boss.post("/api/orders", order("OUT-SAME", null, 2)).ok().get("status").asString()).isEqualTo("BACKORDERED");
        // Shared master data is the same in both, and in use anywhere it can't be deleted.
        boss.delete("/api/items/" + order.get("lines").get(0).get("item").get("id").asString()).is(409);
        boss.delete("/api/container-types/" + lpnType).is(409);
    }

    @Test
    void peopleWorkWhereTheyMay() throws Exception {
        Person eve = new Person();
        JsonNode session = eve.post("/api/rf/session", Map.of("userCode", "EVE", "pin", PIN)).ok();
        assertThat(session.get("facility").get("code").asString()).isEqualTo("EAST");
        assertThat(session.get("facilities")).hasSize(1);
        eve.put("/api/rf/session/facility", Map.of("facilityId", MAIN)).is(400);
        eve.put("/api/rf/session/facility", Map.of("facilityId", UUID.randomUUID())).is(400);
        new Person().put("/api/rf/session/facility", Map.of("facilityId", east)).is(401);
        new Person().put("/api/session/facility", Map.of("facilityId", east)).is(401);
        assertThat(eve.get("/api/rf/session").ok().get("facility").get("code").asString()).isEqualTo("EAST");

        // Someone who may work anywhere starts where they were last.
        device.post("/api/rf/session", Map.of("userCode", "ANN", "pin", PIN)).ok();
        assertThat(device.put("/api/rf/session/facility", Map.of("facilityId", east)).ok().get("facility").get("code").asString()).isEqualTo("EAST");
        Person again = new Person();
        assertThat(again.post("/api/rf/session", Map.of("userCode", "ANN", "pin", PIN)).ok().get("facility").get("code").asString()).isEqualTo("EAST");
        device.put("/api/rf/session/facility", Map.of("facilityId", MAIN)).ok();

        // Nothing half done is left behind.
        boss.post("/api/inventory/counts", Map.of("locationId", "PACK-1")).is(400);
        add("BK-2", null, 4).ok();
        boss.post("/api/inventory/counts", Map.of("locationId", "BK-2"));
        device.post("/api/rf/counts/start", Map.of("locationId", "BK-2")).ok();
        Answer stuck = device.put("/api/rf/session/facility", Map.of("facilityId", east)).is(409);
        assertThat(stuck.body().get("detail").asString()).contains("Counting BK-2");
        String count = device.get("/api/rf/counts").ok().get("counting").get("id").asString();
        // Wherever it is, it shows on the person.
        go(boss, east);
        String ann = boss.get("/api/users?q=ANN").ok().get("content").get(0).get("id").asString();
        assertThat(boss.get("/api/users/" + ann).ok().get("inHand").get(0).asString()).isEqualTo("Counting BK-2");
        go(boss, MAIN);
        device.post("/api/rf/counts/" + count + "/give-back", Map.of()).ok();
        device.put("/api/rf/session/facility", Map.of("facilityId", east)).ok();
        // The same label is another place there.
        device.post("/api/rf/counts/start", Map.of("locationId", "BK-2")).is(400);
        device.put("/api/rf/session/facility", Map.of("facilityId", MAIN)).ok();
    }

    @Test
    void aFacilityIsChangedAndClosed() throws Exception {
        boss.post("/api/facilities", Map.of("code", "EAST", "name", "Another", "status", "ACTIVE")).is(409);
        boss.post("/api/facilities", Map.of("code", "no good", "name", "x", "status", "ACTIVE")).is(400);
        JsonNode west = boss.post("/api/facilities", Map.of("code", "WEST", "name", "West", "address", "1 Road", "status", "ACTIVE")).ok();
        String id = west.get("id").asString();
        go(boss, id);
        Map<String, Object> closed = new HashMap<>(Map.of("code", "WEST", "name", "West", "status", "INACTIVE", "version", 0));
        boss.put("/api/facilities/" + id, closed).is(409); // the boss is in it
        Person limited = new Person();
        user("WES", List.of(id));
        limited.post("/api/rf/session", Map.of("userCode", "WES", "pin", PIN)).ok();
        go(boss, MAIN);
        boss.put("/api/facilities/" + id, Map.of("code", "WEST", "name", "West", "status", "INACTIVE")).is(400);
        boss.put("/api/facilities/" + id, closed).ok();
        boss.put("/api/session/facility", Map.of("facilityId", id)).is(400);
        boss.put("/api/facilities/" + id, closed).is(409); // changed since
        // There is nowhere left for them to work.
        limited.get("/api/rf/putaway").is(403);
        limited.post("/api/rf/session", Map.of("userCode", "WES", "pin", PIN)).is(403);
    }

    @Test
    void stockIsSeenAcrossFacilitiesByThoseWhoMay() throws Exception {
        item("JAR", null, "222");
        stock("JAR", "BK-1", 7);
        go(boss, east);
        stock("JAR", "BK-1", 5);
        assertThat(boss.get("/api/inventory/on-hand?q=JAR").ok().get("content").get(0).get("total").get("total").asInt()).isEqualTo(5);
        JsonNode all = boss.get("/api/inventory/on-hand?q=JAR&everywhere=true").ok().get("content").get(0);
        assertThat(all.get("total").get("total").asInt()).isEqualTo(12);
        assertThat(all.get("total").get("available").asInt()).isEqualTo(12);
        List<String> each = new ArrayList<>();
        all.get("facilities").forEach(f -> each.add(f.get("facility").asString() + " " + f.get("total").get("total").asInt()));
        assertThat(each).containsExactly("EAST 5", "MAIN 7");

        // Someone who only works in EAST only ever sees EAST.
        String viewer = null;
        for (JsonNode r : boss.get("/api/roles").ok()) {
            if (r.get("name").asString().equals("Viewer")) {
                viewer = r.get("id").asString();
            }
        }
        boss.post("/api/users", Map.of("userCode", "VIC", "displayName", "Vic", "status", "ACTIVE", "password", "given for testing", "roleIds", List.of(viewer),
                "facilityIds", List.of(east))).ok();
        Person vic = new Person();
        vic.post("/api/session", Map.of("userCode", "VIC", "password", "given for testing")).ok();
        vic.post("/api/session/password", Map.of("currentPassword", "given for testing", "newPassword", "staple another one")).ok();
        vic.put("/api/session/facility", Map.of("facilityId", MAIN)).is(400);
        assertThat(vic.get("/api/inventory/on-hand?q=JAR&everywhere=true").ok().get("content").get(0).get("total").get("total").asInt()).isEqualTo(5);
        vic.post("/api/facilities", Map.of("code", "X", "name", "x", "status", "ACTIVE")).is(403);
    }

    @Test
    void everyRecordHasAFacility() {
        // Whatever the tests made, nothing refers to something in another facility.
        for (String[] pair : new String[][] {{"inventory", "location_id", "locations"}, {"inventory", "container_id", "containers"},
                {"containers", "current_location_id", "locations"}, {"allocations", "inventory_id", "inventory"}, {"order_lines", "order_id", "outbound_orders"},
                {"zone_locations", "location_id", "locations"}, {"count_tasks", "location_id", "locations"}}) {
            assertThat(db.queryForObject("select count(*) from " + pair[0] + " a join " + pair[2] + " b on b.id = a." + pair[1]
                    + " where a.facility_id <> b.facility_id", Integer.class)).as(pair[0] + "." + pair[1]).isZero();
        }
    }

    // ---------- Owners ----------

    @Test
    void ownersAreKept() throws Exception {
        boss.post("/api/owners", Map.of("code", "ACME", "name", "x", "status", "ACTIVE")).is(409);
        String id = boss.post("/api/owners", Map.of("code", "BETA", "name", "Beta", "status", "ACTIVE")).ok().get("id").asString();
        boss.put("/api/owners/" + id, Map.of("code", "BETA", "name", "Beta Ltd", "status", "INACTIVE", "version", 0)).ok();
        boss.put("/api/owners/" + id, Map.of("code", "BETA", "name", "Beta Ltd", "status", "INACTIVE", "version", 0)).is(409);
        assertThat(boss.post("/api/items", newItem("X", "BETA", null)).is(400).body().get("errors").get("owner").asString()).contains("inactive");
        boss.post("/api/orders", order("B-1", "BETA", 1)).is(400);
        boss.post("/api/orders", order("B-1", "NOBODY", 1)).is(400);
        for (JsonNode o : boss.get("/api/owners").ok()) {
            if (o.get("code").asString().equals("HOUSE")) {
                boss.put("/api/owners/" + o.get("id").asString(), Map.of("code", "HOUSE", "name", "x", "status", "INACTIVE", "version", o.get("version").asInt())).is(409);
            }
        }
    }

    @Test
    void itemsAreTheirOwners() throws Exception {
        boss.post("/api/items", newItem("CUP", "ACME", null)).is(409);
        assertThat(boss.post("/api/items", newItem("CUP2", "ACME", "111")).is(409).body().get("errors").get("barcode").asString()).contains("CUP");
        assertThat(boss.get("/api/items?q=CUP").ok().get("totalElements").asInt()).isEqualTo(2);
        JsonNode theirs = boss.get("/api/items?q=CUP&owner=acme").ok();
        assertThat(theirs.get("totalElements").asInt()).isEqualTo(1);
        JsonNode cup = theirs.get("content").get(0);
        assertThat(cup.get("owner").get("code").asString()).isEqualTo("ACME");
        Map<String, Object> moved = newItem("CUP", "HOUSE", "111");
        moved.put("version", cup.get("version").asInt());
        boss.put("/api/items/" + cup.get("id").asString(), moved).is(400);
        assertThat(boss.get("/api/items?owner=NOBODY").ok().get("totalElements").asInt()).isZero();
    }

    @Test
    void anOwnersStockOnlyGoesToTheirOrders() throws Exception {
        go(boss, east);
        add("BK-1", "ACME", 6).ok();
        add("BK-1", "HOUSE", 3).ok();
        JsonNode order = boss.post("/api/orders", order("A-1", "ACME", 9)).ok();
        assertThat(order.get("owner").get("code").asString()).isEqualTo("ACME");
        assertThat(order.get("status").asString()).isEqualTo("PARTIALLY_ALLOCATED");
        assertThat(order.get("lines").get(0).get("allocatedQuantity").asInt()).isEqualTo(6);
        // Two owners' orders may have the same number, but not one owner's.
        assertThat(boss.post("/api/orders", order("A-1", "HOUSE", 3)).ok().get("status").asString()).isEqualTo("ALLOCATED");
        boss.post("/api/orders", order("A-1", "ACME", 1)).is(409);
        assertThat(boss.get("/api/orders?q=A-1&owner=ACME").ok().get("totalElements").asInt()).isEqualTo(1);
        assertThat(boss.get("/api/inventory/stock?q=BK-1&owner=ACME").ok().get("content").get(0).get("quantity").asInt()).isEqualTo(6);
        assertThat(boss.get("/api/inventory/on-hand?q=CUP&owner=HOUSE").ok().get("content").get(0).get("total").get("total").asInt()).isEqualTo(3);
        JsonNode history = boss.get("/api/inventory/history?q=BK-1&type=ADJUST&owner=ACME").ok();
        assertThat(history.get("totalElements").asInt()).isEqualTo(1);
        assertThat(history.get("content").get(0).get("owner").asString()).isEqualTo("ACME");
        assertThat(order.get("allocations").get(0).get("location").get("locationId").asString()).isEqualTo("BK-1");
        boss.post("/api/orders/" + order.get("id").asString() + "/cancel", Map.of()).ok();
    }

    @Test
    void whoseItIsIsAskedWhenItCouldBeSeveral() throws Exception {
        assertThat(add("BK-1", "", 1).is(409).body().get("errors").get("owner").asString()).isEqualTo("ACME,HOUSE");
        add("BK-1", "NOBODY", 1).is(400);
        add("BK-1", "ACME", 2).ok();
        boss.post("/api/inventory/holds", Map.of("scope", Map.of("itemId", "CUP"), "reasonId", RECALL)).is(409);
        assertThat(boss.post("/api/inventory/holds", Map.of("scope", Map.of("itemId", "CUP", "owner", "ACME"), "reasonId", RECALL)).ok().get("quantity").asInt())
                .isEqualTo(2);
        boss.post("/api/inventory/counts", Map.of("itemId", "CUP")).is(409);

        device.post("/api/rf/session", Map.of("userCode", "ANN", "pin", PIN)).ok();
        device.put("/api/rf/session/facility", Map.of("facilityId", MAIN)).ok();
        assertThat(device.get("/api/rf/counts/items/111").is(409).body().get("errors").get("owner").asString()).isEqualTo("ACME,HOUSE");
        assertThat(device.get("/api/rf/counts/items/111?owner=ACME").ok().get("item").get("owner").asString()).isEqualTo("ACME");
        boss.post("/api/inventory/counts", Map.of("itemId", "CUP", "owner", "ACME"));
        String count = device.post("/api/rf/counts/start", Map.of("locationId", "BK-1")).ok().get("counting").get("id").asString();
        Map<String, Object> entry = new HashMap<>(Map.of("requestId", UUID.randomUUID(), "itemBarcode", "111", "quantity", 2, "serialNumbers", List.of()));
        device.post("/api/rf/counts/" + count + "/entries", entry).is(409);
        entry.put("owner", "ACME");
        device.post("/api/rf/counts/" + count + "/entries", entry).ok();
        device.post("/api/rf/counts/" + count + "/give-back", Map.of()).ok();
    }

    @Test
    void asnsAreTheirOwnersToo() throws Exception {
        go(boss, east);
        Map<String, Object> asn = new HashMap<>(Map.of("asnNumber", "SAME-1", "vendorName", "V", "lines", List.of(Map.of("itemId", "CUP", "quantity", 4))));
        boss.post("/api/asns", asn).ok();
        boss.post("/api/asns", asn).is(409);
        asn.put("owner", "ACME");
        JsonNode theirs = boss.post("/api/asns", asn).ok();
        assertThat(theirs.get("owner").get("code").asString()).isEqualTo("ACME");
        assertThat(boss.get("/api/asns?q=SAME-1&owner=ACME").ok().get("totalElements").asInt()).isEqualTo(1);
        Person eve = new Person();
        eve.post("/api/rf/session", Map.of("userCode", "EVE", "pin", PIN)).ok();
        assertThat(eve.get("/api/rf/receiving/asns/SAME-1").is(409).body().get("errors").get("owner").asString()).isEqualTo("ACME,HOUSE");
        JsonNode open = eve.get("/api/rf/receiving/asns/SAME-1?owner=ACME").ok();
        String type = boss.get("/api/container-types?q=PLT").ok().get("content").get(0).get("id").asString();
        eve.post("/api/rf/receiving/asns/" + open.get("id").asString() + "/lpn", Map.of("locationId", "RCV-1", "lpnId", "LPA")).ok();
        eve.post("/api/rf/receiving/asns/" + open.get("id").asString() + "/receive", Map.of("requestId", UUID.randomUUID(), "locationId", "RCV-1", "lpnId", "LPA",
                "containerTypeId", type, "itemBarcode", "111", "quantity", 4)).ok();
        assertThat(boss.get("/api/inventory/stock?q=LPA").ok().get("content").get(0).get("item").get("owner").asString()).isEqualTo("ACME");
        // Closed, it isn't in question any more.
        eve.post("/api/rf/receiving/asns/" + open.get("id").asString() + "/close", Map.of("acceptShort", false)).ok();
        assertThat(eve.get("/api/rf/receiving/asns/SAME-1").ok().get("owner").get("code").asString()).isEqualTo("HOUSE");
    }

    // ---------- Helpers ----------

    private void go(Person who, String facility) throws Exception {
        who.put("/api/session/facility", Map.of("facilityId", facility)).ok();
    }

    private void user(String code, List<String> facilities) throws Exception {
        String operator = null;
        for (JsonNode r : boss.get("/api/roles").ok()) {
            if (r.get("name").asString().equals("Operator")) {
                operator = r.get("id").asString();
            }
        }
        JsonNode made = boss.post("/api/users", Map.of("userCode", code, "displayName", code, "status", "ACTIVE", "pin", PIN, "roleIds", List.of(operator),
                "facilityIds", facilities)).ok();
        assertThat(made.get("facilities")).hasSize(facilities.size());
    }

    private static Map<String, Object> newItem(String code, String owner, String barcode) {
        Map<String, Object> item = new HashMap<>(Map.of("itemId", code, "description", code, "status", "ACTIVE"));
        item.put("owner", owner);
        item.put("barcode", barcode);
        return item;
    }

    private void item(String code, String owner, String barcode) throws Exception {
        boss.post("/api/items", newItem(code, owner, barcode)).ok();
    }

    private void location(String code, String type) throws Exception {
        Map<String, Object> l = new HashMap<>(Map.of("locationId", code, "type", type, "area", "A", "zone", "Z", "position", code, "width", 99, "depth", 99,
                "height", 99, "dimensionUom", "IN"));
        if (type.equals("STORAGE")) {
            l.putAll(Map.of("aisle", "01", "bay", "01", "level", "A"));
        }
        boss.post("/api/locations", l).ok();
    }

    private Answer add(String location, String owner, int quantity) throws Exception {
        return boss.post("/api/inventory/adjustments/add", Map.of("itemId", "CUP", "owner", owner == null ? "HOUSE" : owner, "locationId", location,
                "quantity", quantity, "reasonId", FOUND));
    }

    private void stock(String item, String location, int quantity) throws Exception {
        boss.post("/api/inventory/adjustments/add", Map.of("itemId", item, "locationId", location, "quantity", quantity, "reasonId", FOUND)).ok();
    }

    private static Map<String, Object> order(String number, String owner, int quantity) {
        Map<String, Object> order = new HashMap<>(Map.of("orderNumber", number, "customerName", "Shop", "lines", List.of(Map.of("itemId", "CUP", "quantity", quantity))));
        order.put("owner", owner);
        return order;
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Content-Type", "application/json");
    }
}
