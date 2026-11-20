package com.wms.outbound;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
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
import com.wms.masterdata.location.Location;
import com.wms.masterdata.location.LocationRepository;
import com.wms.masterdata.location.LocationType;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class OrderAllocationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private ItemRepository items;
    @Autowired private LocationRepository locations;
    @Autowired private InventoryRepository inventory;
    @Autowired private ContainerRepository containers;
    @Autowired private ContainerTypeRepository types;
    @Autowired private OrderService orderService;

    private Item bars;
    private Item tape;
    private Location shelfA;
    private Location shelfB;
    private Location reserve;

    /**
     * Protein bars (lot-tracked): 10 in lot OLD (expires first) on shelf B, 20 in lot NEW on shelf A,
     * 50 in lot HOLD on hold. Tape (not tracked): 5 loose on shelf A, 8 in a carton on a pallet in reserve.
     */
    @BeforeEach
    void fixtures() {
        bars = item("SKU-BAR", true);
        tape = item("SKU-TAPE", false);
        item("SKU-OLD", false).setStatus(ActiveStatus.INACTIVE);
        shelfA = location("DC1-BK-01-01-A-01");
        shelfB = location("DC1-BK-01-01-A-02");
        reserve = location("DC1-RS-01-01-A-01");

        stock(bars, 20, "NEW", "2027-06-01", InventoryStatus.AVAILABLE, shelfA, null);
        stock(bars, 10, "OLD", "2027-01-01", InventoryStatus.AVAILABLE, shelfB, null);
        stock(bars, 50, "HOLD", "2026-12-01", InventoryStatus.ON_HOLD, shelfA, null);
        stock(tape, 5, null, null, InventoryStatus.AVAILABLE, shelfA, null);

        ContainerType palletType = type("PLT", ContainerCategory.PALLET);
        ContainerType cartonType = type("CTN", ContainerCategory.CARTON);
        Container pallet = lpn("P1", palletType);
        pallet.setCurrentLocation(reserve);
        containers.save(pallet);
        Container carton = lpn("BOX-77", cartonType);
        carton.setParent(pallet);
        containers.save(carton);
        stock(tape, 8, null, null, InventoryStatus.AVAILABLE, null, carton);
    }

    @Test
    void allocatesEarliestExpiryFirstAndCreatesPickTasks() throws Exception {
        create(order("SO-1", line("sku-bar", 25, null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderNumber").value("SO-1"))
                .andExpect(jsonPath("$.status").value("ALLOCATED"))
                .andExpect(jsonPath("$.lines[0].allocatedQuantity").value(25))
                .andExpect(jsonPath("$.lines[0].status").value("ALLOCATED"))
                .andExpect(jsonPath("$.allocations", hasSize(2)))
                .andExpect(jsonPath("$.allocations[0].lotNumber").value("OLD"))
                .andExpect(jsonPath("$.allocations[0].quantity").value(10))
                .andExpect(jsonPath("$.allocations[0].location.locationId").value("DC1-BK-01-01-A-02"))
                .andExpect(jsonPath("$.allocations[1].lotNumber").value("NEW"))
                .andExpect(jsonPath("$.allocations[1].quantity").value(15))
                .andExpect(jsonPath("$.tasks", hasSize(1)))
                .andExpect(jsonPath("$.tasks[0].taskNumber", matchesPattern("PT\\d{6}")))
                .andExpect(jsonPath("$.tasks[0].status").value("OPEN"))
                .andExpect(jsonPath("$.tasks[0].detailCount").value(2))
                .andExpect(jsonPath("$.tasks[0].lineCount").value(1))
                .andExpect(jsonPath("$.tasks[0].locationCount").value(2))
                .andExpect(jsonPath("$.tasks[0].details[0].detailNumber").value(1))
                .andExpect(jsonPath("$.tasks[0].details[0].fromLocation.locationId").value("DC1-BK-01-01-A-02"))
                .andExpect(jsonPath("$.tasks[0].details[0].quantity").value(10))
                .andExpect(jsonPath("$.tasks[0].details[1].detailNumber").value(2))
                .andExpect(jsonPath("$.allocations[0].task.detailNumber").value(1))
                .andExpect(jsonPath("$.allocations[1].task.detailNumber").value(2))
                .andExpect(jsonPath("$.events[0].type").value("RECEIVED"))
                .andExpect(jsonPath("$.events[1].type").value("ALLOCATED"))
                .andExpect(jsonPath("$.events[2].type").value("TASKS_CREATED"))
                .andExpect(jsonPath("$.events[2].message", containsString("with 2 details")));
    }

    @Test
    void partiallyAllocatesWhenStockRunsShort() throws Exception {
        create(order("SO-1", line("SKU-BAR", 40, null)))
                .andExpect(jsonPath("$.status").value("PARTIALLY_ALLOCATED"))
                .andExpect(jsonPath("$.lines[0].allocatedQuantity").value(30))
                .andExpect(jsonPath("$.lines[0].status").value("PARTIALLY_ALLOCATED"))
                .andExpect(jsonPath("$.events[1].type").value("SHORT"))
                .andExpect(jsonPath("$.events[1].message", containsString("10 EA short")));
    }

    @Test
    void backordersWhenOnlyHeldStockMatchesTheRequestedLot() throws Exception {
        create(order("SO-1", line("SKU-BAR", 5, "HOLD")))
                .andExpect(jsonPath("$.status").value("BACKORDERED"))
                .andExpect(jsonPath("$.lines[0].status").value("BACKORDERED"))
                .andExpect(jsonPath("$.lines[0].requestedLot").value("HOLD"))
                .andExpect(jsonPath("$.tasks", hasSize(0)))
                .andExpect(jsonPath("$.events[1].message", containsString("no available stock")));
    }

    @Test
    void allocatesOnlyTheRequestedLot() throws Exception {
        create(order("SO-1", line("SKU-BAR", 5, "NEW")))
                .andExpect(jsonPath("$.status").value("ALLOCATED"))
                .andExpect(jsonPath("$.allocations[0].lotNumber").value("NEW"));
    }

    @Test
    void doesNotReserveTheSameStockTwice() throws Exception {
        create(order("SO-1", line("SKU-BAR", 25, null))).andExpect(jsonPath("$.status").value("ALLOCATED"));
        create(order("SO-2", line("SKU-BAR", 25, null)))
                .andExpect(jsonPath("$.status").value("PARTIALLY_ALLOCATED"))
                .andExpect(jsonPath("$.lines[0].allocatedQuantity").value(5))
                .andExpect(jsonPath("$.allocations[0].lotNumber").value("NEW"));
    }

    @Test
    void picksStockInsideContainersFromTheOuterContainersLocation() throws Exception {
        create(order("SO-1", line("SKU-TAPE", 12, null)))
                .andExpect(jsonPath("$.status").value("ALLOCATED"))
                .andExpect(jsonPath("$.tasks", hasSize(1)))
                .andExpect(jsonPath("$.tasks[0].details[?(@.fromContainer.lpnId == 'BOX-77')].fromLocation.locationId").value("DC1-RS-01-01-A-01"))
                .andExpect(jsonPath("$.tasks[0].details[?(@.fromContainer == null)].fromLocation.locationId").value("DC1-BK-01-01-A-01"));
    }

    @Test
    void generatesAnOrderNumberWhenNoneIsGiven() throws Exception {
        create(order(null, line("SKU-TAPE", 1, null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderNumber", matchesPattern("SO\\d{6}")))
                .andExpect(jsonPath("$.priority").value(5));
    }

    @Test
    void rejectsBadOrders() throws Exception {
        create("{\"customerName\":\"\",\"lines\":[]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.customerName").exists())
                .andExpect(jsonPath("$.errors.lines").value("add at least one line"));

        create(order("SO-1", line("NOPE", 1, null) + "," + line("SKU-OLD", 1, null) + "," + line("SKU-TAPE", 1, "L1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['lines[0].itemId']").value("item NOPE not found"))
                .andExpect(jsonPath("$.errors['lines[1].itemId']").value("SKU-OLD is inactive"))
                .andExpect(jsonPath("$.errors['lines[2].requestedLot']").value("SKU-TAPE isn't lot-tracked"));

        create(order("SO-1", line("SKU-TAPE", 1, null))).andExpect(status().isCreated());
        create(order("so-1", line("SKU-TAPE", 1, null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors.orderNumber").value("already used by another order"));
    }

    @Test
    void allocatesAgainAfterStockArrives() throws Exception {
        String id = id(create(order("SO-1", line("SKU-TAPE", 20, null))));
        mvc.perform(get("/api/orders/" + id)).andExpect(jsonPath("$.status").value("PARTIALLY_ALLOCATED"));

        stock(tape, 10, null, null, InventoryStatus.AVAILABLE, shelfB, null);
        mvc.perform(post("/api/orders/" + id + "/allocate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ALLOCATED"))
                .andExpect(jsonPath("$.lines[0].allocatedQuantity").value(20))
                .andExpect(jsonPath("$.tasks", hasSize(2)))
                .andExpect(jsonPath("$.tasks[1].details", hasSize(1)));

        mvc.perform(post("/api/orders/" + id + "/allocate"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("already fully allocated")));
    }

    @Test
    void cancelReleasesStockAndCancelsTasks() throws Exception {
        String id = id(create(order("SO-1", line("SKU-BAR", 30, null))));

        mvc.perform(post("/api/orders/" + id + "/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.lines[0].status").value("CANCELLED"))
                .andExpect(jsonPath("$.allocations", hasSize(0)))
                .andExpect(jsonPath("$.tasks[0].status").value("CANCELLED"))
                .andExpect(jsonPath("$.tasks[0].details[0].status").value("CANCELLED"))
                .andExpect(jsonPath("$.tasks[0].details[1].status").value("CANCELLED"))
                .andExpect(jsonPath("$.events[?(@.type == 'CANCELLED')].message",
                        hasItem(matchesPattern("Order cancelled: released 30 EA and cancelled 2 details of pick task PT\\d{6}"))));

        create(order("SO-2", line("SKU-BAR", 30, null))).andExpect(jsonPath("$.status").value("ALLOCATED"));
        mvc.perform(post("/api/orders/" + id + "/cancel")).andExpect(status().isConflict());
    }

    @Test
    void listsOrdersAndPickTasks() throws Exception {
        create(order("SO-1", line("SKU-BAR", 25, null)));
        create(order("SO-2", line("SKU-BAR", 99, null)));

        mvc.perform(get("/api/orders"))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].orderNumber").value("SO-2"))
                .andExpect(jsonPath("$.content[0].orderedQuantity").value(99))
                .andExpect(jsonPath("$.content[0].allocatedQuantity").value(5))
                .andExpect(jsonPath("$.content[1].openTaskCount").value(1));
        mvc.perform(get("/api/orders").param("status", "ALLOCATED"))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].orderNumber").value("SO-1"));
        mvc.perform(get("/api/orders").param("q", "acme"))
                .andExpect(jsonPath("$.totalElements").value(2));

        mvc.perform(get("/api/pick-tasks"))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].orders[0].orderNumber").value("SO-2"))
                .andExpect(jsonPath("$.content[0].pickMethod").value("DISCRETE"))
                .andExpect(jsonPath("$.content[1].details", hasSize(2)));
        mvc.perform(get("/api/pick-tasks").param("q", "a-02"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void eachAllocationBecomesADetailOfTheOrdersPickTask() throws Exception {
        String body = create(order("SO-1", line("SKU-BAR", 12, null) + "," + line("SKU-TAPE", 12, null)))
                .andExpect(jsonPath("$.allocations", hasSize(4)))
                .andExpect(jsonPath("$.tasks", hasSize(1)))
                .andExpect(jsonPath("$.tasks[0].detailCount").value(4))
                .andExpect(jsonPath("$.tasks[0].lineCount").value(2))
                .andExpect(jsonPath("$.tasks[0].totalQuantity").value(24))
                .andExpect(jsonPath("$.tasks[0].details[0].lineNumber").value(1))
                .andExpect(jsonPath("$.tasks[0].details[3].lineNumber").value(2))
                .andExpect(jsonPath("$.tasks[0].details[3].detailNumber").value(4))
                .andReturn().getResponse().getContentAsString();
        String taskId = mapper.readTree(body).get("tasks").get(0).get("id").asString();

        mvc.perform(get("/api/pick-tasks/" + taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders[0].orderNumber").value("SO-1"))
                .andExpect(jsonPath("$.details[0].order.orderNumber").value("SO-1"))
                .andExpect(jsonPath("$.details", hasSize(4)))
                .andExpect(jsonPath("$.details[0].allocationId").exists());
        mvc.perform(get("/api/pick-tasks/" + UUID.randomUUID())).andExpect(status().isNotFound());
        mvc.perform(get("/api/pick-tasks").param("q", "box-77"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void listsAllAllocationsWithTheirOrderAndPickTask() throws Exception {
        create(order("SO-1", line("SKU-BAR", 12, null)));
        create(order("SO-2", line("SKU-TAPE", 12, null)));

        mvc.perform(get("/api/allocations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.totalAllocatedQuantity").value(24))
                .andExpect(jsonPath("$.content[0].order.orderNumber").exists())
                .andExpect(jsonPath("$.content[0].order.customerName").value("Acme Retail"))
                .andExpect(jsonPath("$.content[0].task.taskNumber", matchesPattern("PT\\d{6}")))
                .andExpect(jsonPath("$.content[0].task.status").value("OPEN"));

        mvc.perform(get("/api/allocations").param("q", "so-1"))
                .andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(get("/api/allocations").param("q", "old"))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].lotNumber").value("OLD"));
        mvc.perform(get("/api/allocations").param("q", "box-7"))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].container.lpnId").value("BOX-77"))
                .andExpect(jsonPath("$.content[0].location.locationId").value("DC1-RS-01-01-A-01"));
        mvc.perform(get("/api/allocations").param("q", "rs-01"))
                .andExpect(jsonPath("$.content", hasSize(1)));
        mvc.perform(get("/api/allocations").param("orderStatus", "ALLOCATED"))
                .andExpect(jsonPath("$.totalElements").value(4));
        mvc.perform(get("/api/allocations").param("inventoryStatus", "ON_HOLD"))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    /** Receives an order without allocating it, so it can be batched. */
    private String received(String number, String itemId, int quantity) {
        return orderService.create(new OrderRequest(number, "Acme Retail", null, 5,
                List.of(new OrderRequest.Line(itemId, quantity, null))), false).id().toString();
    }

    private ResultActions batch(String... orderIds) throws Exception {
        String ids = String.join(",", java.util.Arrays.stream(orderIds).map(id -> "\"" + id + "\"").toList());
        return mvc.perform(post("/api/orders/batch-allocate").contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderIds\":[" + ids + "]}"));
    }

    @Test
    void batchPicksSeveralOrdersOnOneTask() throws Exception {
        String a = received("B-1", "SKU-BAR", 12);
        String b = received("B-2", "SKU-TAPE", 12);

        // Listed in any order, details are numbered by order number.
        String body = batch(b, a)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.pickMethod").value("BATCH"))
                .andExpect(jsonPath("$.task.taskNumber", matchesPattern("PT\\d{6}")))
                .andExpect(jsonPath("$.task.orders", hasSize(2)))
                .andExpect(jsonPath("$.task.orders[0].orderNumber").value("B-1"))
                .andExpect(jsonPath("$.task.orders[1].orderNumber").value("B-2"))
                .andExpect(jsonPath("$.task.lineCount").value(2))
                .andExpect(jsonPath("$.task.totalQuantity").value(24))
                .andExpect(jsonPath("$.task.details[0].order.orderNumber").value("B-1"))
                .andExpect(jsonPath("$.task.details[0].detailNumber").value(1))
                .andExpect(jsonPath("$.task.details[-1].order.orderNumber").value("B-2"))
                .andExpect(jsonPath("$.orders[0].status").value("ALLOCATED"))
                .andExpect(jsonPath("$.orders[1].status").value("ALLOCATED"))
                .andReturn().getResponse().getContentAsString();
        String taskId = mapper.readTree(body).get("task").get("id").asString();
        String taskNumber = mapper.readTree(body).get("task").get("taskNumber").asString();

        // Each order sees the batch task, and says so in its activity.
        mvc.perform(get("/api/orders/" + a))
                .andExpect(jsonPath("$.tasks", hasSize(1)))
                .andExpect(jsonPath("$.tasks[0].id").value(taskId))
                .andExpect(jsonPath("$.allocations[0].task.taskNumber").value(taskNumber))
                .andExpect(jsonPath("$.events[?(@.type == 'TASKS_CREATED')].message",
                        hasItem(containsString("Added to batch pick task " + taskNumber + " with 1 other order"))));
        mvc.perform(get("/api/orders"))
                .andExpect(jsonPath("$.content[?(@.orderNumber == 'B-1')].openTaskCount").value(1))
                .andExpect(jsonPath("$.content[?(@.orderNumber == 'B-2')].openTaskCount").value(1));

        mvc.perform(get("/api/pick-tasks").param("pickMethod", "BATCH"))
                .andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/pick-tasks").param("pickMethod", "DISCRETE"))
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/pick-tasks").param("q", "b-2"))
                .andExpect(jsonPath("$.totalElements").value(1));

        // Cancelling one order leaves the other's picks on the task.
        mvc.perform(post("/api/orders/" + a + "/cancel"))
                .andExpect(jsonPath("$.tasks[0].status").value("OPEN"))
                .andExpect(jsonPath("$.events[?(@.type == 'CANCELLED')].message",
                        hasItem(containsString("of pick task " + taskNumber))));
        mvc.perform(get("/api/pick-tasks/" + taskId))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.details[?(@.order.orderNumber == 'B-1')].status", everyItem(is("CANCELLED"))))
                .andExpect(jsonPath("$.details[?(@.order.orderNumber == 'B-2')].status", everyItem(is("OPEN"))));
        mvc.perform(post("/api/orders/" + b + "/cancel"));
        mvc.perform(get("/api/pick-tasks/" + taskId)).andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void batchNeedsTwoOrdersThatStillNeedStock() throws Exception {
        String a = received("B-1", "SKU-BAR", 5);
        batch(a).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.orderIds").exists());
        batch(a, a).andExpect(status().isBadRequest());
        batch(a, UUID.randomUUID().toString()).andExpect(status().isNotFound());

        String done = id(create(order("SO-9", line("SKU-TAPE", 2, null))));
        batch(a, done).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors.orderIds", containsString("SO-9 is already fully allocated")));
        mvc.perform(get("/api/orders/" + a)).andExpect(jsonPath("$.status").value("NEW"));
    }

    @Test
    void ordersCanBeReceivedWithoutAllocating() throws Exception {
        mvc.perform(post("/api/orders").param("allocate", "false").contentType(MediaType.APPLICATION_JSON)
                        .content(order("SO-1", line("SKU-BAR", 5, null))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("NEW"))
                .andExpect(jsonPath("$.tasks", hasSize(0)));
    }

    @Test
    void batchWithNoStockCreatesNoTask() throws Exception {
        String a = received("B-1", "SKU-BAR", 5);
        String b = received("B-2", "SKU-BAR", 5);
        inventory.deleteAll();
        batch(a, b)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task").doesNotExist())
                .andExpect(jsonPath("$.orders[0].status").value("BACKORDERED"));
    }

    @Test
    void cancelledOrdersLeaveNoAllocations() throws Exception {
        String id = id(create(order("SO-1", line("SKU-BAR", 12, null))));
        mvc.perform(post("/api/orders/" + id + "/cancel"));

        mvc.perform(get("/api/allocations"))
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.totalAllocatedQuantity").value(0));
    }

    @Test
    void orderDetailShowsEachAllocationsPickTask() throws Exception {
        create(order("SO-1", line("SKU-BAR", 12, null)))
                .andExpect(jsonPath("$.allocations[0].task.taskNumber", matchesPattern("PT\\d{6}")))
                .andExpect(jsonPath("$.allocations[0].order.orderNumber").value("SO-1"));
    }

    @Test
    void inventoryViewsShowWhatIsAllocated() throws Exception {
        create(order("SO-1", line("SKU-BAR", 12, null)));

        mvc.perform(get("/api/inventory/locations/" + shelfB.getId()))
                .andExpect(jsonPath("$.lines[0].quantity").value(10))
                .andExpect(jsonPath("$.lines[0].allocatedQuantity").value(10));
        mvc.perform(get("/api/inventory/locations/" + shelfA.getId()))
                .andExpect(jsonPath("$.lines[?(@.lotNumber == 'NEW')].allocatedQuantity").value(2));
    }

    @Test
    void itemsOnOpenOrdersCannotBeDeleted() throws Exception {
        Item spare = item("SKU-SPARE", false);
        create(order("SO-1", line("SKU-SPARE", 1, null)));

        mvc.perform(delete("/api/items/" + spare.getId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("on 1 open order line")));
    }

    // ---------- helpers ----------

    private ResultActions create(String body) throws Exception {
        return mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String id(ResultActions result) throws Exception {
        return mapper.readTree(result.andReturn().getResponse().getContentAsString()).get("id").asString();
    }

    private static String order(String number, String lines) {
        return """
                {"orderNumber":%s,"customerName":"Acme Retail","requestedShipDate":"2026-12-01","lines":[%s]}
                """.formatted(number == null ? "null" : "\"" + number + "\"", lines);
    }

    private static String line(String itemId, int quantity, String lot) {
        return "{\"itemId\":\"%s\",\"quantity\":%d,\"requestedLot\":%s}".formatted(
                itemId, quantity, lot == null ? "null" : "\"" + lot + "\"");
    }

    private Item item(String code, boolean lotControlled) {
        Item i = new Item(UUID.randomUUID());
        i.setItemId(code);
        i.setDescription(code);
        i.setStatus(ActiveStatus.ACTIVE);
        i.setLotControlled(lotControlled);
        i.setExpiryControlled(lotControlled);
        return items.save(i);
    }

    private Location location(String code) {
        Location l = new Location(UUID.randomUUID());
        l.setLocationId(code);
        l.setType(LocationType.STORAGE);
        l.setArea("DC1");
        l.setZone(code.substring(4, 6));
        l.setAisle(code.substring(7, 9));
        l.setBay(code.substring(10, 12));
        l.setLevel(code.substring(13, 14));
        l.setPosition(code.substring(15, 17));
        l.setWidth(BigDecimal.TEN);
        l.setDepth(BigDecimal.TEN);
        l.setHeight(BigDecimal.TEN);
        l.setDimensionUom(DimensionUom.IN);
        return locations.save(l);
    }

    private ContainerType type(String code, ContainerCategory category) {
        ContainerType t = new ContainerType(UUID.randomUUID());
        t.setContainerTypeId(code);
        t.setDescription(code);
        t.setCategory(category);
        t.setStatus(ActiveStatus.ACTIVE);
        t.setReusable(category == ContainerCategory.PALLET);
        t.setWidth(BigDecimal.ONE);
        t.setDepth(BigDecimal.ONE);
        t.setHeight(BigDecimal.ONE);
        t.setDimensionUom(DimensionUom.IN);
        return types.save(t);
    }

    private static Container lpn(String code, ContainerType type) {
        Container c = new Container(UUID.randomUUID());
        c.setLpnId(code);
        c.setContainerType(type);
        c.setStatus(ContainerStatus.IN_USE);
        c.setPermanent(false);
        return c;
    }

    private void stock(Item item, int qty, String lot, String expiry, InventoryStatus status, Location location, Container container) {
        InventoryRecord r = new InventoryRecord(UUID.randomUUID(), item, qty);
        r.setLotNumber(lot);
        r.setExpiryDate(expiry == null ? null : LocalDate.parse(expiry));
        r.setStatus(status);
        r.setReceivedAt(Instant.parse("2026-11-01T10:00:00Z"));
        if (location != null) {
            r.placeIn(location);
        } else {
            r.placeIn(container);
        }
        inventory.save(r);
    }
}
