package com.wms.outbound;

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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The thin slice of outbound, from incoming order to shipped, the way a warehouse runs it:
 * over HTTP, every step committed, against the demo warehouse in a database of its own.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:wms-slice;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "wms.demo-data.enabled=true",
        "wms.demo-data.user-pin=9753"})
class OutboundSliceTest {

    private static final String STATION = "DC1-PACK-01";
    private static final String DOOR = "DC1-DOCK-01";

    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate db;
    @Value("${local.server.port}") private int port;

    /** Each thread is someone else, with a sign-in of their own. */
    private final ThreadLocal<HttpClient> http = ThreadLocal.withInitial(
            () -> HttpClient.newBuilder().cookieHandler(new CookieManager()).build());
    private static int nextCarton;

    @BeforeEach
    void signIn() throws Exception {
        Map<String, Object> admin = Map.of("userCode", "BOSS", "displayName", "The Boss", "password", "correct horse battery");
        boolean first = get("/api/session").get("state").asString().equals("SETUP");
        post(first ? "/api/session/setup" : "/api/session", admin);
        post("/api/rf/session", Map.of("userCode", "PICKER1", "pin", "9753"));
        // The demo orders are already waiting; everything here is more urgent, so it is picked first.
    }

    /** Whatever happened, the books must balance. */
    @AfterEach
    void stockAddsUp() {
        none("more reserved than there is", """
                select i.id from inventory i join allocations a on a.inventory_id = i.id
                group by i.id, i.quantity having sum(a.quantity) > i.quantity""");
        none("stock the history doesn't add up to", """
                select t.item_id from items t
                where (select coalesce(sum(i.quantity), 0) from inventory i where i.item_id = t.id)
                <> (select coalesce(sum(case when h.from_location is null and h.from_lpn is null then h.quantity else 0 end)
                        - sum(case when h.to_location is null and h.to_lpn is null then h.quantity else 0 end), 0)
                    from inventory_transactions h where h.item_id = t.id)""");
        none("stock on hold without a reason, or good with one", """
                select i.id from inventory i where i.status = 'AVAILABLE' and i.hold_reason is not null""");
        none("a line further along than it can be", """
                select id from order_lines where shipped_quantity > packed_quantity or packed_quantity > picked_quantity
                or picked_quantity > allocated_quantity or allocated_quantity > ordered_quantity""");
        none("stock in a container that is out of service", """
                select i.id from inventory i join containers c on c.id = i.container_id where c.status = 'RETIRED'""");
        none("stock still held for an order line beyond what it needs", """
                select l.id from order_lines l left join allocations a on a.order_line_id = l.id
                group by l.id, l.allocated_quantity, l.shipped_quantity
                having coalesce(sum(a.quantity), 0) <> l.allocated_quantity - l.shipped_quantity""");
        none("a tote in use with nothing going on", """
                select c.id from containers c join container_types t on t.id = c.container_type_id
                where t.category = 'TOTE' and c.status = 'IN_USE'
                and not exists (select 1 from pick_tasks p where p.to_container_id = c.id and p.status in ('IN_PROGRESS', 'PICKED'))
                and not exists (select 1 from pack_tasks k where k.container_id = c.id and k.status <> 'COMPLETED')""");
    }

    @Test
    void anOrderGoesFromReceivedToShipped() throws Exception {
        long glasses = onHand("SKU-10003");
        long drills = onHand("SKU-10001");

        // An order comes in and is allocated, with a pick task.
        JsonNode order = post("/api/orders", order("SLICE-1", 1, line("SKU-10003", 15), line("SKU-10001", 1)));
        assertThat(order.get("status").asString()).isEqualTo("ALLOCATED");
        assertThat(order.get("tasks")).hasSize(1);

        // It is picked into a tote and dropped at a pack station.
        String id = order.get("id").asString();
        pickInto("TOTE00001");
        assertThat(status(id)).isEqualTo("PICKED");
        assertThat(get("/api/orders/" + id).get("packTasks")).hasSize(1);

        // It is packed into a carton, which makes a shipment.
        packAll("TOTE00001");
        assertThat(status(id)).isEqualTo("PACKED");
        assertThat(get("/api/containers?q=TOTE00001").get("content").get(0).get("status").asString()).isEqualTo("AVAILABLE");
        JsonNode shipment = get("/api/orders/" + id).get("shipments").get(0);
        assertThat(shipment.get("missing")).hasSize(2);
        assertThat(onHand("SKU-10003")).isEqualTo(glasses);

        // The shipment is prepared, loaded and shipped.
        prepare(shipment, id, "DPARCEL");
        ship("DPARCEL", List.of(shipment));
        JsonNode done = get("/api/orders/" + id);
        assertThat(done.get("status").asString()).isEqualTo("SHIPPED");
        assertThat(done.get("allocations")).isEmpty();
        assertThat(onHand("SKU-10003")).isEqualTo(glasses - 15);
        assertThat(onHand("SKU-10001")).isEqualTo(drills - 1);

        // Its activity tells the whole story, in order.
        List<String> types = new ArrayList<>();
        done.get("events").forEach(e -> {
            String type = e.get("type").asString();
            if (types.isEmpty() || !types.getLast().equals(type)) {
                types.add(type);
            }
        });
        assertThat(types).containsSubsequence("RECEIVED", "ALLOCATED", "TASKS_CREATED", "PICKED", "DROPPED", "TASKS_CREATED", "PACKED", "SHIPPED");
    }

    @Test
    void aDeliveryIsReceivedPutAwayPickedPackedAndShipped() throws Exception {
        long before = onHand("SKU-10002");

        // The vendor announces a delivery, and it is received into a new LPN.
        JsonNode asn = post("/api/asns", Map.of("asnNumber", "SLICE-IN", "vendorName", "Acme", "lines", List.of(line("SKU-10002", 3))));
        String receiving = "/api/rf/receiving/asns/" + asn.get("id").asString();
        JsonNode lpn = post(receiving + "/lpn", Map.of("locationId", "DC1-RCV-01", "lpnId", ""));
        String lpnId = lpn.get("lpnId").asString();
        String carton = null;
        for (JsonNode type : lpn.get("types")) {
            if (type.get("containerTypeId").asString().startsWith("CTN")) {
                carton = type.get("id").asString();
            }
        }
        JsonNode item = asn.get("lines").get(0).get("item");
        Map<String, Object> receipt = new java.util.HashMap<>(Map.of("requestId", java.util.UUID.randomUUID(), "locationId", "DC1-RCV-01",
                "lpnId", lpnId, "containerTypeId", carton, "itemBarcode", "SKU-10002", "quantity", 3));
        if (item.get("serialControlled").asBoolean()) {
            receipt.put("serialNumbers", List.of("SLICE-1", "SLICE-2", "SLICE-3"));
        }
        post(receiving + "/receive", receipt);
        post(receiving + "/close", Map.of("acceptShort", false));
        assertThat(onHand("SKU-10002")).isEqualTo(before + 3);

        // It can't be ordered until it is put away where the rules say.
        long storedBefore = allocatable("SKU-10002");
        JsonNode task = post("/api/rf/putaway/start", Map.of("lpnId", lpnId)).get("task");
        assertThat(task.get("ruleName").asString()).isEqualTo("Everything else to shelving");
        post("/api/rf/putaway/tasks/" + task.get("id").asString() + "/confirm",
                Map.of("locationId", task.get("directedLocation").get("locationId").asString()));
        assertThat(allocatable("SKU-10002")).isEqualTo(storedBefore + 3);

        // All of it is ordered, down to the last one.
        JsonNode order = post("/api/orders", order("SLICE-IO", 1, line("SKU-10002", (int) storedBefore + 3)));
        assertThat(order.get("status").asString()).isEqualTo("ALLOCATED");
        String id = order.get("id").asString();
        pickInto("TOTE00007");
        packAll("TOTE00007");
        JsonNode shipment = get("/api/orders/" + id).get("shipments").get(0);
        prepare(shipment, id, "DPARCEL");
        ship("DPARCEL", List.of(shipment));
        assertThat(status(id)).isEqualTo("SHIPPED");
        assertThat(allocatable("SKU-10002")).isZero();
    }

    /** Good stock in storage that no order has reserved. */
    private long allocatable(String itemId) {
        Long quantity = db.queryForObject("""
                with recursive place(id, location_id, container_id) as (
                    select i.id, i.location_id, i.container_id from inventory i
                    union all
                    select p.id, c.current_location_id, c.parent_container_id from place p join containers c on c.id = p.container_id)
                select coalesce(sum(i.quantity - (select coalesce(sum(a.quantity), 0) from allocations a where a.inventory_id = i.id)), 0)
                from inventory i join items t on t.id = i.item_id
                where t.item_id = ? and i.status = 'AVAILABLE' and exists (select 1 from place p join locations l on l.id = p.location_id
                    where p.id = i.id and l.location_type = 'STORAGE')""", Long.class, itemId);
        return quantity == null ? 0 : quantity;
    }

    @Test
    void twoOrdersArePickedTogetherAndShippedOnOneManifest() throws Exception {
        JsonNode a = post("/api/orders?allocate=false", order("SLICE-A", 1, line("SKU-10004", 2)));
        JsonNode b = post("/api/orders?allocate=false", order("SLICE-B", 1, line("SKU-10004", 3)));
        assertThat(a.get("status").asString()).isEqualTo("NEW");
        JsonNode batch = post("/api/orders/batch-allocate", Map.of("orderIds", List.of(a.get("id").asString(), b.get("id").asString())));
        assertThat(batch.get("task").get("pickMethod").asString()).isEqualTo("BATCH");
        pickInto("TOTE00002");
        packAll("TOTE00002");
        List<JsonNode> shipments = new ArrayList<>();
        for (JsonNode order : List.of(a, b)) {
            JsonNode shipment = get("/api/orders/" + order.get("id").asString()).get("shipments").get(0);
            prepare(shipment, order.get("id").asString(), "DFREIGHT");
            shipments.add(shipment);
        }
        JsonNode manifest = ship("DFREIGHT", shipments);
        assertThat(manifest.get("shipments")).hasSize(2);
        assertThat(status(a.get("id").asString())).isEqualTo("SHIPPED");
        assertThat(status(b.get("id").asString())).isEqualTo("SHIPPED");
    }

    @Test
    void aShortPickIsRefilledAndTheRestFollowsOnASecondShipment() throws Exception {
        String id = post("/api/orders", order("SLICE-S", 1, line("SKU-10003", 6))).get("id").asString();
        JsonNode state = post("/api/rf/picking/start", Map.of("lpnId", "TOTE00003"));
        assertThat(state.get("pick").get("orderNumber").asString()).isEqualTo("SLICE-S");
        confirm(state, 4, true);
        post("/api/rf/picking/drop", Map.of("locationId", STATION));
        packAll("TOTE00003");
        JsonNode first = get("/api/orders/" + id).get("shipments").get(0);
        prepare(first, id, "DFREIGHT");
        ship("DFREIGHT", List.of(first));
        assertThat(status(id)).isEqualTo("PARTIALLY_SHIPPED");

        // The missing two are found elsewhere and follow.
        JsonNode again = post("/api/orders/" + id + "/allocate", Map.of());
        assertThat(again.get("lines").get(0).get("allocatedQuantity").asInt()).isEqualTo(6);
        pickInto("TOTE00003");
        packAll("TOTE00003");
        JsonNode second = get("/api/orders/" + id).get("shipments").get(1);
        assertThat(second.get("missing").toString()).doesNotContain("address");
        prepare(second, null, "DFREIGHT");
        ship("DFREIGHT", List.of(second));
        assertThat(status(id)).isEqualTo("SHIPPED");
    }

    @Test
    void theDemoOrdersCanAllBeShippedAsFarAsStockAllows() throws Exception {
        // Everything waiting is picked, packed and shipped, however it was split.
        int guard = 0;
        while (get("/api/rf/picking").get("waitingTasks").asInt() > 0 && guard++ < 20) {
            String tote = "TOTE000" + (10 + guard);
            pickInto(tote);
            packAll(tote);
        }
        List<JsonNode> shipments = new ArrayList<>();
        for (JsonNode s : get("/api/shipments?status=OPEN&size=100").get("content")) {
            prepare(s, s.get("order").get("id").asString(), "DFREIGHT");
            shipments.add(s);
        }
        assertThat(shipments).isNotEmpty();
        ship("DFREIGHT", shipments);
        for (JsonNode o : get("/api/orders?size=100").get("content")) {
            assertThat(o.get("status").asString()).as(o.get("orderNumber").asString())
                    .isIn("SHIPPED", "PARTIALLY_SHIPPED", "BACKORDERED");
        }
    }

    @Test
    void anOrderThatCantBeFilledIsClosedShort() throws Exception {
        JsonNode order = post("/api/orders", order("SLICE-C", 1, line("SKU-40001", 2), line("SKU-40001", 100_000)));
        String id = order.get("id").asString();
        pickInto("TOTE00005");
        packAll("TOTE00005");
        JsonNode shipment = get("/api/orders/" + id).get("shipments").get(0);
        prepare(shipment, id, "DFREIGHT");
        ship("DFREIGHT", List.of(shipment));
        assertThat(status(id)).isEqualTo("PARTIALLY_SHIPPED");
        assertThat(post("/api/orders/" + id + "/close-short", Map.of()).get("status").asString()).isEqualTo("SHIPPED");
    }

    @Test
    void aPickLeftHalfDoneIsFinishedBySomeoneElse() throws Exception {
        String id = post("/api/orders", order("SLICE-T", 1, line("SKU-40002", 4))).get("id").asString();
        JsonNode state = post("/api/rf/picking/start", Map.of("lpnId", "TOTE00006"));
        confirm(state, 1, false);
        post("/api/rf/session", Map.of("userCode", "PICKER2", "pin", "9753"));
        state = post("/api/rf/picking/start", Map.of("lpnId", "TOTE00006", "takeOver", true));
        while (state.get("step").asString().equals("PICK")) {
            state = confirm(state, state.get("pick").get("remaining").asInt(), false);
        }
        post("/api/rf/picking/drop", Map.of("locationId", STATION));
        packAll("TOTE00006");
        assertThat(status(id)).isEqualTo("PACKED");
    }

    @Test
    void anOrderInTwoPickZonesIsPickedByTwoPeopleAtOnce() throws Exception {
        String reserve = pickZone("RS");
        String shelves = pickZone("BK");
        try {
            JsonNode order = post("/api/orders", order("SLICE-Z", 1, line("SKU-20003", 6), line("SKU-30002", 4)));
            String id = order.get("id").asString();
            assertThat(order.get("status").asString()).isEqualTo("ALLOCATED");
            assertThat(order.get("tasks")).hasSize(2);

            // One each at a time, so they keep changing the same order together.
            CompletableFuture<Void> one = CompletableFuture.runAsync(() -> pickOneByOne("PICKER1", "TOTE00008", reserve));
            CompletableFuture<Void> two = CompletableFuture.runAsync(() -> pickOneByOne("PICKER2", "TOTE00009", shelves));
            one.get(60, TimeUnit.SECONDS);
            two.get(60, TimeUnit.SECONDS);
            assertThat(status(id)).isEqualTo("PICKED");
            packAll("TOTE00008");
            packAll("TOTE00009");
            assertThat(status(id)).isEqualTo("PACKED");
        } finally {
            send(request("/api/zones/" + reserve).DELETE());
            send(request("/api/zones/" + shelves).DELETE());
        }
    }

    // ---------- What a person does at each step ----------

    private String pickZone(String addressZone) throws Exception {
        String id = post("/api/zones", Map.of("function", "PICKING", "code", addressZone, "name", addressZone, "status", "ACTIVE")).get("id").asString();
        post("/api/zones/" + id + "/locations", Map.of("filter", Map.of("area", "DC1", "zone", addressZone)));
        return id;
    }

    private void pickOneByOne(String user, String lpn, String zone) {
        try {
            post("/api/rf/session", Map.of("userCode", user, "pin", "9753"));
            JsonNode state = post("/api/rf/picking/start", Map.of("lpnId", lpn, "zoneId", zone));
            while (state.get("step").asString().equals("PICK")) {
                state = confirm(state, 1, false);
            }
            post("/api/rf/picking/drop", Map.of("locationId", STATION));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Picks the most urgent task into a container, doing what the device says, and drops it. */
    private void pickInto(String lpn) throws Exception {
        JsonNode state = post("/api/rf/picking/start", Map.of("lpnId", lpn));
        while (state.get("step").asString().equals("PICK")) {
            state = confirm(state, state.get("pick").get("remaining").asInt(), false);
        }
        assertThat(state.get("step").asString()).isEqualTo("DROP");
        post("/api/rf/picking/drop", Map.of("locationId", STATION));
    }

    private JsonNode confirm(JsonNode state, int quantity, boolean shortPick) throws Exception {
        JsonNode pick = state.get("pick");
        Map<String, Object> body = new HashMap<>();
        body.put("detailId", pick.get("detailId").asString());
        body.put("pickedSoFar", pick.get("pickedQuantity").asInt());
        body.put("locationId", pick.get("locationId").asString());
        body.put("fromLpnId", text(pick.get("fromLpnId")));
        body.put("itemBarcode", pick.get("item").get("itemId").asString());
        body.put("serialNumber", text(pick.get("serialNumber")));
        body.put("quantity", quantity);
        body.put("shortPick", shortPick);
        return post("/api/rf/picking/confirm", body);
    }

    /** Packs everything in a container, one carton per order. */
    private void packAll(String lpn) throws Exception {
        JsonNode task = post("/api/rf/packing/start", Map.of("locationId", STATION, "lpnId", lpn));
        String url = "/api/rf/packing/tasks/" + task.get("id").asString();
        String type = null;
        for (JsonNode t : get("/api/rf/packing/carton-types")) {
            if (t.get("containerTypeId").asString().equals("CTN-L")) {
                type = t.get("id").asString();
            }
        }
        for (JsonNode order : task.get("orders")) {
            String orderId = order.get("id").asString();
            task = post(url + "/cartons", Map.of("orderId", orderId, "containerTypeId", type, "lpnId", "SLICE-BOX-" + ++nextCarton));
            String carton = null;
            for (JsonNode c : task.get("cartons")) {
                if (c.get("status").asString().equals("OPEN")) {
                    carton = c.get("id").asString();
                }
            }
            for (JsonNode d : task.get("details")) {
                if (d.get("order").get("id").asString().equals(orderId) && d.get("remaining").asInt() > 0) {
                    Map<String, Object> scan = new HashMap<>();
                    scan.put("cartonId", carton);
                    scan.put("packedSoFar", task.get("packedQuantity").asInt());
                    scan.put("itemBarcode", d.get("item").get("itemId").asString());
                    scan.put("serialNumber", text(d.get("serialNumber")));
                    scan.put("lotNumber", text(d.get("lotNumber")));
                    scan.put("quantity", d.get("remaining").asInt());
                    task = post(url + "/pack", scan);
                }
            }
            task = post(url + "/cartons/" + carton + "/close", Map.of("weight", 2.5, "weightUom", "LB"));
        }
        assertThat(task.get("status").asString()).isEqualTo("COMPLETED");
    }

    /** Gives a shipment its address (unless the order has one), carrier service and tracking numbers. */
    private void prepare(JsonNode shipment, String orderId, String carrierCode) throws Exception {
        String id = shipment.get("id").asString();
        if (orderId != null) {
            put("/api/orders/" + orderId + "/ship-to", Map.of("name", "Slice", "line1", "1 Main St", "city", "Springfield",
                    "postalCode", "62701", "country", "US"));
        }
        JsonNode carrier = carrier(carrierCode);
        put("/api/shipments/" + id + "/service", Map.of("carrierServiceId", carrier.get("services").get(0).get("id").asString(),
                "version", get("/api/shipments/" + id).get("version").asLong()));
        JsonNode current = get("/api/shipments/" + id);
        if (carrier.get("trackingRequired").asBoolean()) {
            for (JsonNode c : current.get("cartons")) {
                current = put("/api/shipments/" + id + "/cartons/" + c.get("id").asString() + "/tracking",
                        Map.of("trackingNumber", "TRK-" + c.get("container").get("lpnId").asString()));
            }
        }
        assertThat(current.get("missing")).isEmpty();
    }

    /** Loads every carton of these shipments at a door and closes the manifest. */
    private JsonNode ship(String carrierCode, List<JsonNode> shipments) throws Exception {
        JsonNode door = post("/api/rf/loading/doors/" + DOOR + "/manifest", Map.of("carrierId", carrier(carrierCode).get("id").asString()));
        String url = "/api/rf/loading/manifests/" + door.get("manifest").get("id").asString();
        for (JsonNode s : shipments) {
            for (JsonNode c : s.get("cartons")) {
                post(url + "/load", Map.of("lpnId", c.get("container").get("lpnId").asString()));
            }
        }
        JsonNode closed = post(url + "/close", Map.of("trailerNumber", "T1", "sealNumber", "S1", "leaveBehind", false));
        assertThat(closed.get("status").asString()).isEqualTo("CLOSED");
        return closed;
    }

    private JsonNode carrier(String code) throws Exception {
        for (JsonNode c : get("/api/carriers")) {
            if (c.get("carrierCode").asString().equals(code)) {
                return c;
            }
        }
        throw new AssertionError("no carrier " + code);
    }

    private String status(String orderId) throws Exception {
        return get("/api/orders/" + orderId).get("status").asString();
    }

    private long onHand(String itemId) {
        return db.queryForObject("select coalesce(sum(i.quantity), 0) from inventory i join items t on t.id = i.item_id where t.item_id = ?",
                Long.class, itemId);
    }

    private void none(String what, String sql) {
        assertThat(db.queryForList(sql)).as(what).isEmpty();
    }

    private static Map<String, Object> order(String number, int priority, Object... lines) {
        return Map.of("orderNumber", number, "customerName", "Slice Co", "priority", priority, "lines", List.of(lines));
    }

    private static Map<String, Object> line(String itemId, int quantity) {
        return Map.of("itemId", itemId, "quantity", quantity);
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? null : node.asString();
    }

    private JsonNode get(String path) throws Exception {
        return send(request(path).GET());
    }

    private JsonNode post(String path, Object body) throws Exception {
        return send(request(path).POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))));
    }

    private JsonNode put(String path, Object body) throws Exception {
        return send(request(path).PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))));
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Content-Type", "application/json");
    }

    private JsonNode send(HttpRequest.Builder request) throws Exception {
        HttpRequest r = request.build();
        HttpResponse<String> response = http.get().send(r, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(r.method() + " " + r.uri().getPath() + " -> " + response.body()).isBetween(200, 299);
        return mapper.readTree(response.body());
    }
}
