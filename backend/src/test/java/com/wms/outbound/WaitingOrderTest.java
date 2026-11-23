package com.wms.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Orders short of stock get it by themselves. Every step is committed, in a database of its own. */
@SpringBootTest(properties = {"wms.orders.allocate-waiting=true", "wms.orders.waiting-interval-seconds=3600",
        "spring.datasource.url=jdbc:h2:mem:wms-waiting;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
class WaitingOrderTest {

    private static final String FOUND = "00000000-0000-4000-8001-000000000001";
    private static final String RECALL = "00000000-0000-4000-8001-000000000007";
    private static final String CHECKED = "00000000-0000-4000-8001-000000000008";
    private static int next;

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private WaitingOrderRun run;

    /** An item of its own for each test, and the same two shelves. */
    private String item;

    @BeforeEach
    void fixtures() throws Exception {
        item = "CUP-" + ++next;
        call(post("/api/items"), Map.of("itemId", item, "description", "Cup", "status", "ACTIVE"));
        if (call(get("/api/locations?q=BK-1"), null).get("totalElements").asInt() == 0) {
            for (String code : List.of("BK-1", "BK-2")) {
                call(post("/api/locations"), Map.ofEntries(Map.entry("locationId", code), Map.entry("type", "STORAGE"), Map.entry("area", "A"),
                        Map.entry("zone", "Z"), Map.entry("aisle", "01"), Map.entry("bay", "01"), Map.entry("level", "A"), Map.entry("position", code),
                        Map.entry("width", 99), Map.entry("depth", 99), Map.entry("height", 99), Map.entry("dimensionUom", "IN")));
            }
        }
    }

    @Test
    void theMostUrgentGetsWhatComesFirst() throws Exception {
        String later = order(10, 5, true);
        String urgent = order(6, 1, true);
        assertThat(status(later)).isEqualTo("BACKORDERED");
        add("BK-1", 8);
        until(() -> status(urgent).equals("ALLOCATED"));
        until(() -> status(later).equals("PARTIALLY_ALLOCATED"));
        JsonNode got = call(get("/api/orders/" + later), null);
        assertThat(got.get("lines").get(0).get("allocatedQuantity").asInt()).isEqualTo(2);
        assertThat(got.get("tasks")).hasSize(1);
        add("BK-2", 20);
        until(() -> status(later).equals("ALLOCATED"));
    }

    @Test
    void releasedStockAndACancelledOrdersStockGoToThoseWaiting() throws Exception {
        add("BK-1", 5);
        call(post("/api/inventory/holds"), Map.of("scope", Map.of("itemId", item), "reasonId", RECALL));
        String first = order(5, 5, true);
        String second = order(5, 5, true);
        assertThat(status(first)).isEqualTo("BACKORDERED");
        String held = call(get("/api/inventory/stock?q=" + item), null).get("content").get(0).get("id").asString();
        call(post("/api/inventory/holds/release"), Map.of("inventoryIds", List.of(held), "reasonId", CHECKED));
        until(() -> status(first).equals("ALLOCATED"));
        assertThat(status(second)).isEqualTo("BACKORDERED");
        call(post("/api/orders/" + first + "/cancel"), null);
        until(() -> status(second).equals("ALLOCATED"));
    }

    @Test
    void ordersLeftForSomeoneToAllocateAreLeftAlone() throws Exception {
        String notYet = order(3, 1, false);
        add("BK-1", 3);
        String undone = order(3, 1, true);
        assertThat(status(undone)).isEqualTo("ALLOCATED");
        call(post("/api/orders/" + undone + "/undo"), null);
        String waiting = order(5, 9, true);
        add("BK-1", 2);
        until(() -> status(waiting).equals("ALLOCATED"));
        assertThat(status(notYet)).isEqualTo("NEW");
        assertThat(status(undone)).isEqualTo("NEW");
    }

    @Test
    void tryingWithNothingThereChangesNothing() throws Exception {
        String waiting = order(5, 5, true);
        JsonNode before = call(get("/api/orders/" + waiting), null);
        run.all();
        JsonNode after = call(get("/api/orders/" + waiting), null);
        assertThat(after.get("version")).isEqualTo(before.get("version"));
        assertThat(after.get("events")).hasSameSizeAs(before.get("events"));
    }

    // ---------- Helpers ----------

    private interface Check {
        boolean holds() throws Exception;
    }

    private static void until(Check check) throws Exception {
        Instant end = Instant.now().plus(Duration.ofSeconds(10));
        while (!check.holds()) {
            assertThat(Instant.now()).as("in time").isBefore(end);
            Thread.sleep(20);
        }
    }

    private String order(int quantity, int priority, boolean allocate) throws Exception {
        return call(post("/api/orders?allocate=" + allocate), Map.of("customerName", "Shop", "priority", priority,
                "lines", List.of(Map.of("itemId", item, "quantity", quantity)))).get("id").asString();
    }

    private String status(String order) throws Exception {
        return call(get("/api/orders/" + order), null).get("status").asString();
    }

    private void add(String location, int quantity) throws Exception {
        call(post("/api/inventory/adjustments/add"), Map.of("itemId", item, "locationId", location, "quantity", quantity, "reasonId", FOUND));
    }

    private JsonNode call(MockHttpServletRequestBuilder request, Object body) throws Exception {
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
        }
        var response = mvc.perform(request).andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isBetween(200, 299);
        return response.getContentAsString().isEmpty() ? null : mapper.readTree(response.getContentAsString());
    }
}
