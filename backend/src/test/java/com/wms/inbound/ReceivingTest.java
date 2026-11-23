package com.wms.inbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
class ReceivingTest {

    private static final String PIN = "1357";
    private static final String RCV = "DC1-RCV-01";
    private static final LocalDate TODAY = LocalDate.now();

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

    private Item bars;
    private ContainerType pallet;
    private Cookie ann;

    /** Bars (lot and expiry, 6 to a case), drills (serials), plain cups; a pallet type and tote T1. */
    @BeforeEach
    void fixtures() throws Exception {
        location(RCV, LocationType.RECEIVING, "01");
        location("DC1-RCV-02", LocationType.RECEIVING, "02");
        location("DC1-BK-01-01-A-01", LocationType.STORAGE, "01");

        bars = item("SKU-BAR", "111000");
        bars.setLotControlled(true);
        bars.setExpiryControlled(true);
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
        item("SKU-DRILL", "222000").setSerialControlled(true);
        item("SKU-CUP", "333000");
        item("SKU-OTHER", "444000");

        pallet = type("PLT", ContainerCategory.PALLET, false);
        type("BIN", ContainerCategory.BIN, true);
        containers.save(tote("T1", type("TOTE", ContainerCategory.TOTE, true)));
        people.operator("ANN", "Ann Lee", PIN);
        ann = signIn("ann");
    }

    // ---------- ASNs ----------

    @Test
    void anAsnIsCreatedListedAndFound() throws Exception {
        String id = asn("ASN-1", line("SKU-BAR", 60, "L1", TODAY.plusDays(90), "VPLT-1"), line("SKU-CUP", 10));
        call(get("/api/asns/" + id), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXPECTED"))
                .andExpect(jsonPath("$.expectedQuantity").value(70))
                .andExpect(jsonPath("$.lines", hasSize(2)))
                .andExpect(jsonPath("$.lines[0].lotNumber").value("L1"))
                .andExpect(jsonPath("$.lines[0].lpnId").value("VPLT-1"))
                .andExpect(jsonPath("$.events[0].message").value("ASN from Acme: 2 lines, 70 EA"));
        for (String q : List.of("asn-1", "acme", "po-9", "sku-cup", "vplt")) {
            call(get("/api/asns").param("q", q), null).andExpect(jsonPath("$.totalElements").value(1));
        }
        call(get("/api/asns").param("q", "asn_1"), null).andExpect(jsonPath("$.totalElements").value(0));
        call(get("/api/asns").param("status", "RECEIVED"), null).andExpect(jsonPath("$.totalElements").value(0));
        call(get("/api/asns").param("sort", "nope"), null).andExpect(status().isBadRequest());
        call(get("/api/asns/" + UUID.randomUUID()), null).andExpect(status().isNotFound());
        call(json(post("/api/asns"), request(null, line("SKU-CUP", 1))), null).andExpect(status().isCreated())
                .andExpect(jsonPath("$.asnNumber", matchesPattern("AS\\d{6}")));
        call(delete("/api/items/" + bars.getId()), null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("1 ASN line")));
    }

    @Test
    void mistakesOnAnAsnArePointedOut() throws Exception {
        asn("ASN-1", line("SKU-CUP", 1, null, null, "VPLT-1"));
        call(json(post("/api/asns"), request("asn-1", line("SKU-CUP", 1))), null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors.asnNumber").exists());
        call(json(post("/api/asns"), request("ASN-2", line("NOPE", 1), line("SKU-CUP", 1, "L1", TODAY, null),
                line("SKU-CUP", 1, null, null, "T1"), line("SKU-CUP", 1, null, null, RCV), line("SKU-CUP", 1, null, null, "333000"))), null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['lines[0].itemId']").exists())
                .andExpect(jsonPath("$.errors['lines[1].lotNumber']").exists())
                .andExpect(jsonPath("$.errors['lines[1].expiryDate']").exists())
                .andExpect(jsonPath("$.errors['lines[2].lpnId']", containsString("already in the warehouse")))
                .andExpect(jsonPath("$.errors['lines[3].lpnId']", containsString("is a location")))
                .andExpect(jsonPath("$.errors['lines[4].lpnId']", containsString("is an item")));
        call(json(post("/api/asns"), request("ASN-2", line("SKU-CUP", 1, null, null, "vplt-1"))), null)
                .andExpect(jsonPath("$.errors['lines[0].lpnId']", containsString("another open ASN")));
        call(json(post("/api/asns"), Map.of("vendorName", "Acme", "lines", List.of())), null).andExpect(status().isBadRequest());
    }

    @Test
    void onlyAnUntouchedAsnCanBeCancelled() throws Exception {
        String id = asn("ASN-1", line("SKU-CUP", 5));
        call(post("/api/asns/" + id + "/cancel"), null).andExpect(jsonPath("$.status").value("CANCELLED"));
        call(post("/api/asns/" + id + "/cancel"), null).andExpect(status().isConflict());
        call(get("/api/rf/receiving/asns/ASN-1"), ann).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("ASN-1 is cancelled"));
        String other = asn("ASN-2", line("SKU-CUP", 5));
        receive(other, "LP1", "333000", 1, Map.of()).andExpect(status().isOk());
        call(post("/api/asns/" + other + "/cancel"), null).andExpect(status().isConflict());
        receive(id, "LP9", "333000", 1, Map.of()).andExpect(status().isConflict());
    }

    // ---------- Starting ----------

    @Test
    void receivingNeedsASignInAReceivingLocationAndAnOpenAsn() throws Exception {
        asn("ASN-1", line("SKU-CUP", 5));
        mvc.perform(get("/api/rf/receiving/asns")).andExpect(status().isUnauthorized());
        call(get("/api/rf/receiving/locations"), ann).andExpect(jsonPath("$", hasSize(2)));
        call(get("/api/rf/receiving/locations/dc1-rcv-01"), ann).andExpect(jsonPath("$.locationId").value(RCV));
        call(get("/api/rf/receiving/locations/DC1-BK-01-01-A-01"), ann).andExpect(status().isBadRequest());
        call(get("/api/rf/receiving/asns"), ann).andExpect(jsonPath("$", hasSize(1)));
        call(get("/api/rf/receiving/asns/asn-1"), ann).andExpect(jsonPath("$.lines[0].item.scans", hasSize(2)));
        call(get("/api/rf/receiving/asns/NOPE"), ann).andExpect(status().isBadRequest());
    }

    @Test
    void aScannedLpnIsChecked() throws Exception {
        String id = asn("ASN-1", line("SKU-CUP", 5), line("SKU-BAR", 6, "L1", TODAY.plusDays(9), "VPLT-1"));
        checkLpn(id, "LP1").andExpect(jsonPath("$.kind").value("NEW")).andExpect(jsonPath("$.needsType").value(true))
                .andExpect(jsonPath("$.types[*].containerTypeId", hasItem("PLT")))
                .andExpect(jsonPath("$.types", hasSize(2)));
        checkLpn(id, "").andExpect(jsonPath("$.lpnId", matchesPattern("LP\\d{8}")));
        checkLpn(id, "vplt-1").andExpect(jsonPath("$.kind").value("ANNOUNCED")).andExpect(jsonPath("$.whyNotAll").doesNotExist())
                .andExpect(jsonPath("$.expected", hasSize(1)));
        checkLpn(id, "T1").andExpect(jsonPath("$.kind").value("OPEN")).andExpect(jsonPath("$.needsType").value(false));
        checkLpn(id, RCV).andExpect(jsonPath("$.errors.lpnId", containsString("is a location")));
        checkLpn(id, "111006").andExpect(jsonPath("$.errors.lpnId", containsString("is an item")));
        receive(id, "T1", "333000", 1, Map.of()).andExpect(status().isOk());
        checkLpn(id, "T1").andExpect(jsonPath("$.kind").value("OPEN"));
        call(json(post("/api/rf/receiving/asns/" + id + "/lpn"), Map.of("locationId", "DC1-RCV-02", "lpnId", "T1")), ann)
                .andExpect(jsonPath("$.errors.lpnId").value("T1 is at " + RCV));
        String other = asn("ASN-2", line("SKU-CUP", 5));
        checkLpn(other, "T1").andExpect(jsonPath("$.errors.lpnId").value("T1 is already in use"));
    }

    @Test
    void aDeliveryCanBeReceivedAtADockDoor() throws Exception {
        location("DC1-DOCK-01", LocationType.DOCK, "01");
        String id = asn("ASN-1", line("SKU-CUP", 5));
        call(get("/api/rf/receiving/locations"), ann).andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[2].locationId").value("DC1-DOCK-01")).andExpect(jsonPath("$[2].type").value("Dock door"));
        call(get("/api/rf/receiving/locations/dc1-dock-01"), ann).andExpect(status().isOk());
        JsonNode asn = body(receive(id, "LP1", "333000", 5, Map.of("locationId", "DC1-DOCK-01")));
        assertThat(container("LP1").getCurrentLocation().getLocationId()).isEqualTo("DC1-DOCK-01");
        // It is the same as anywhere else: no more of it elsewhere, and it can be undone.
        receive(id, "LP1", "333000", 1, Map.of("allowOver", true)).andExpect(jsonPath("$.errors.lpnId").value("LP1 is at DC1-DOCK-01"));
        call(post("/api/rf/receiving/receipts/" + asn.get("receipts").get(0).get("id").asString() + "/reverse"), ann).andExpect(status().isOk())
                .andExpect(jsonPath("$.receivedQuantity").value(0));
    }

    // ---------- Blind ----------

    @Test
    void whatNobodyAnnouncedCanBeReceived() throws Exception {
        UUID start = UUID.randomUUID();
        JsonNode begun = null;
        for (int i = 0; i < 2; i++) {
            begun = body(blind(start, "Acme", "PO-77").andExpect(jsonPath("$.asnNumber", matchesPattern("BR\\d{6}")))
                    .andExpect(jsonPath("$.blind").value(true)).andExpect(jsonPath("$.status").value("RECEIVING"))
                    .andExpect(jsonPath("$.vendorName").value("Acme")).andExpect(jsonPath("$.lines", hasSize(0))));
        }
        String id = begun.get("id").asString();
        String number = begun.get("asnNumber").asString();
        call(get("/api/rf/receiving/asns"), ann).andExpect(jsonPath("$", hasSize(1))).andExpect(jsonPath("$[0].blind").value(true));
        call(get("/api/rf/receiving/blind/" + id + "/items/111006"), ann).andExpect(jsonPath("$.itemId").value("SKU-BAR"))
                .andExpect(jsonPath("$.lotControlled").value(true));
        call(get("/api/rf/receiving/blind/" + id + "/items/NOPE"), ann).andExpect(status().isBadRequest());

        checkLpn(id, "LP1").andExpect(jsonPath("$.kind").value("NEW"));
        receive(id, "LP1", "NOPE", 1, Map.of()).andExpect(jsonPath("$.errors.itemBarcode").exists());
        receive(id, "LP1", "111006", 2, Map.of()).andExpect(jsonPath("$.errors.lotNumber").exists());
        receive(id, "LP1", "111006", 2, Map.of("lotNumber", "L1", "expiryDate", TODAY.plusDays(90))).andExpect(status().isOk())
                .andExpect(jsonPath("$.lines", hasSize(1))).andExpect(jsonPath("$.receivedQuantity").value(12))
                .andExpect(jsonPath("$.expectedQuantity").value(0)).andExpect(jsonPath("$.lines[0].remaining").value(0))
                .andExpect(jsonPath("$.events[*].message", hasItem("Ann Lee received 12 EA of SKU-BAR lot L1 into LP1 at " + RCV)));
        UUID again = UUID.randomUUID();
        for (int i = 0; i < 2; i++) {
            receive(id, "LP1", "111000", 3, Map.of("requestId", again, "lotNumber", "L1", "expiryDate", TODAY.plusDays(90))).andExpect(status().isOk())
                    .andExpect(jsonPath("$.lines", hasSize(1))).andExpect(jsonPath("$.receivedQuantity").value(15));
        }
        receive(id, "LP1", "111000", 1, Map.of("lotNumber", "L2", "expiryDate", TODAY.plusDays(90))).andExpect(jsonPath("$.lines", hasSize(2)));
        receive(id, "LP2", "222000", 2, Map.of("serialNumbers", List.of("D1", "D2"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.lines", hasSize(3))).andExpect(jsonPath("$.receipts[0].quantity").value(1));
        assertThat(stock("LP1")).extracting(InventoryRecord::getQuantity).containsExactlyInAnyOrder(15, 1);

        JsonNode detail = body(call(get("/api/asns/" + id), null));
        call(post("/api/rf/receiving/receipts/" + detail.get("receipts").get(0).get("id").asString() + "/reverse"), ann)
                .andExpect(jsonPath("$.receivedQuantity").value(17));
        call(get("/api/putaway/tasks"), null).andExpect(jsonPath("$.totalElements").value(2));
        close(id, false).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.events[*].message", hasItem("Ann Lee closed it: 17 EA received")));
        receive(id, "LP3", "333000", 1, Map.of()).andExpect(status().isConflict());
        call(get("/api/asns").param("q", number), null).andExpect(jsonPath("$.content[0].blind").value(true));
        call(get("/api/inventory/history").param("q", number), null).andExpect(status().isOk());
    }

    @Test
    void anAnnouncedLpnIsntReceivedBlind() throws Exception {
        asn("ASN-1", line("SKU-CUP", 10, null, null, "VPLT-1"));
        String id = body(blind(UUID.randomUUID(), null, null)).get("id").asString();
        checkLpn(id, "VPLT-1").andExpect(jsonPath("$.errors.lpnId").value("VPLT-1 is announced on ASN-1. Receive it there"));
        receive(id, "VPLT-1", "333000", 1, Map.of()).andExpect(status().isBadRequest());
    }

    @Test
    void aBlindReceiptOfNothingIsDiscarded() throws Exception {
        String announced = asn("ASN-1", line("SKU-CUP", 10));
        call(post("/api/rf/receiving/blind/" + announced + "/discard"), ann).andExpect(status().isConflict());
        JsonNode begun = body(blind(UUID.randomUUID(), " ", null).andExpect(jsonPath("$.vendorName").value("Vendor not known")));
        String id = begun.get("id").asString();
        close(id, true).andExpect(status().isConflict());
        call(post("/api/rf/receiving/blind/" + id + "/discard"), ann).andExpect(jsonPath("$.status").value("CANCELLED"));
        String other = body(blind(UUID.randomUUID(), null, null)).get("id").asString();
        receive(other, "LP1", "333000", 1, Map.of()).andExpect(status().isOk());
        call(post("/api/rf/receiving/blind/" + other + "/discard"), ann).andExpect(status().isConflict());
        blind(null, null, null).andExpect(status().isBadRequest());
        blind(UUID.randomUUID(), "V".repeat(121), null).andExpect(jsonPath("$.errors.vendorName").exists());
    }

    @Test
    void inactiveItemsArentReceivedBlind() throws Exception {
        items.findAllByItemId("SKU-CUP").getFirst().setStatus(ActiveStatus.INACTIVE);
        String id = body(blind(UUID.randomUUID(), null, null)).get("id").asString();
        receive(id, "LP1", "333000", 1, Map.of()).andExpect(jsonPath("$.errors.itemBarcode").value("SKU-CUP is inactive"));
        assertThat(containers.findByLpnId("LP1")).isEmpty();
    }

    // ---------- Receiving items ----------

    @Test
    void casesAreReceivedIntoANewLpn() throws Exception {
        String id = asn("ASN-1", line("SKU-BAR", 60));
        receive(id, "LP1", "111006", 2, Map.of("lotNumber", "l1", "expiryDate", TODAY.plusDays(90)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECEIVING"))
                .andExpect(jsonPath("$.lines[0].receivedQuantity").value(12))
                .andExpect(jsonPath("$.lines[0].remaining").value(48))
                .andExpect(jsonPath("$.receipts[0].receivedBy").value("Ann Lee"))
                .andExpect(jsonPath("$.events[*].message",
                        hasItem("Ann Lee received 12 EA of SKU-BAR lot L1 into LP1 at " + RCV)));
        Container lpn = container("LP1");
        assertThat(lpn.getStatus()).isEqualTo(ContainerStatus.IN_USE);
        assertThat(lpn.getCurrentLocation().getLocationId()).isEqualTo(RCV);
        assertThat(lpn.isPermanent()).isFalse();
        // The same goes together; another lot stays apart.
        receive(id, "LP1", "111000", 3, Map.of("lotNumber", "L1", "expiryDate", TODAY.plusDays(90))).andExpect(status().isOk());
        receive(id, "LP1", "111000", 1, Map.of("lotNumber", "L2", "expiryDate", TODAY.plusDays(90))).andExpect(status().isOk());
        assertThat(stock("LP1")).extracting(InventoryRecord::getQuantity).containsExactlyInAnyOrder(15, 1);
    }

    @Test
    void whatTheItemNeedsMustBeCaptured() throws Exception {
        String id = asn("ASN-1", line("SKU-BAR", 60), line("SKU-DRILL", 5));
        receive(id, "LP1", "111000", 1, Map.of("expiryDate", TODAY)).andExpect(jsonPath("$.errors.lotNumber").exists());
        receive(id, "LP1", "111000", 1, Map.of("lotNumber", "L1")).andExpect(jsonPath("$.errors.expiryDate").exists());
        receive(id, "LP1", "111000", 1, Map.of("lotNumber", "L1", "expiryDate", TODAY.minusDays(1)))
                .andExpect(jsonPath("$.errors.expiryDate", containsString("already expired")));
        receive(id, "LP1", "111000", 1, Map.of("lotNumber", "L1", "expiryDate", TODAY.minusDays(1), "condition", "ON_HOLD"))
                .andExpect(status().isOk());
        receive(id, "LP1", "222000", 2, Map.of("serialNumbers", List.of("D1"))).andExpect(jsonPath("$.errors.serialNumbers").exists());
        receive(id, "LP1", "222000", 2, Map.of("serialNumbers", List.of("D1", "d1"))).andExpect(jsonPath("$.errors.serialNumbers").exists());
        receive(id, "LP1", "222000", 2, Map.of("serialNumbers", List.of("D1", "D2"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.receipts", hasSize(3)));
        receive(id, "LP1", "222000", 1, Map.of("serialNumbers", List.of("D2")))
                .andExpect(jsonPath("$.errors.serialNumbers").value("D2 has already been received"));
        receive(id, "LP1", "444000", 1, Map.of()).andExpect(jsonPath("$.errors.itemBarcode").value("444000 is not on ASN-1"));
        receive(id, "LP1", "222000", 0, Map.of()).andExpect(jsonPath("$.errors.quantity").exists());
    }

    @Test
    void aNewLpnNeedsAType() throws Exception {
        String id = asn("ASN-1", line("SKU-CUP", 5));
        Map<String, Object> body = new HashMap<>(Map.of("requestId", UUID.randomUUID(), "locationId", RCV, "lpnId", "LP1",
                "itemBarcode", "333000", "quantity", 1));
        call(json(post("/api/rf/receiving/asns/" + id + "/receive"), body), ann)
                .andExpect(jsonPath("$.errors.containerTypeId").exists());
        assertThat(containers.findByLpnId("LP1")).isEmpty();
    }

    @Test
    void stockInReceivingCannotBeAllocated() throws Exception {
        String id = asn("ASN-1", line("SKU-CUP", 5));
        receive(id, "LP1", "333000", 5, Map.of()).andExpect(status().isOk());
        call(json(post("/api/orders"), Map.of("customerName", "Cafe", "lines", List.of(Map.of("itemId", "SKU-CUP", "quantity", 1)))), null)
                .andExpect(jsonPath("$.status").value("BACKORDERED"));
    }

    @Test
    void theSameRequestIsReceivedOnce() throws Exception {
        String id = asn("ASN-1", line("SKU-CUP", 10));
        UUID request = UUID.randomUUID();
        for (int i = 0; i < 2; i++) {
            receive(id, "LP1", "333000", 4, Map.of("requestId", request)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.receivedQuantity").value(4));
        }
        assertThat(stock("LP1")).extracting(InventoryRecord::getQuantity).containsExactly(4);
        receive(id, "LP1", "333000", 4, Map.of("requestId", "")).andExpect(status().isBadRequest());
    }

    @Test
    void receivingMoreThanExpectedIsConfirmed() throws Exception {
        String id = asn("ASN-1", line("SKU-CUP", 5), line("SKU-CUP", 5));
        receive(id, "LP1", "333000", 12, Map.of()).andExpect(status().isConflict()).andExpect(jsonPath("$.errors.over").value("2"));
        assertThat(containers.findByLpnId("LP1")).isEmpty();
        receive(id, "LP1", "333000", 12, Map.of("allowOver", true)).andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].receivedQuantity").value(7))
                .andExpect(jsonPath("$.lines[1].receivedQuantity").value(5))
                .andExpect(jsonPath("$.events[*].message", hasItem(containsString("2 EA over"))));
        assertThat(stock("LP1")).extracting(InventoryRecord::getQuantity).containsExactly(12);
        close(id, false).andExpect(status().isOk()).andExpect(jsonPath("$.events[*].message", hasItem(containsString("2 over"))));
    }

    @Test
    void theLineWithTheSameLotIsFilledFirst() throws Exception {
        LocalDate expiry = TODAY.plusDays(90);
        String id = asn("ASN-1", line("SKU-BAR", 6, "L1", expiry, null), line("SKU-BAR", 6, "L2", expiry, null));
        receive(id, "LP1", "111006", 1, Map.of("lotNumber", "L2", "expiryDate", expiry))
                .andExpect(jsonPath("$.lines[0].receivedQuantity").value(0))
                .andExpect(jsonPath("$.lines[1].receivedQuantity").value(6));
    }

    @Test
    void conditionIsKeptOnTheStock() throws Exception {
        String id = asn("ASN-1", line("SKU-CUP", 5));
        receive(id, "LP1", "333000", 2, Map.of("condition", "DAMAGED")).andExpect(status().isOk());
        receive(id, "LP1", "333000", 3, Map.of("condition", "AVAILABLE")).andExpect(status().isOk());
        assertThat(stock("LP1")).extracting(InventoryRecord::getStatus)
                .containsExactlyInAnyOrder(InventoryStatus.DAMAGED, InventoryStatus.AVAILABLE);
    }

    // ---------- Announced LPNs ----------

    @Test
    void anAnnouncedLpnIsReceivedWhole() throws Exception {
        LocalDate expiry = TODAY.plusDays(90);
        String id = asn("ASN-1", line("SKU-BAR", 60, "L1", expiry, "VPLT-1"), line("SKU-CUP", 4, null, null, "VPLT-1"));
        UUID request = UUID.randomUUID();
        for (int i = 0; i < 2; i++) {
            receiveLpn(id, "VPLT-1", request).andExpect(status().isOk()).andExpect(jsonPath("$.receivedQuantity").value(64));
        }
        assertThat(stock("VPLT-1")).hasSize(2);
        receiveLpn(id, "VPLT-1", UUID.randomUUID()).andExpect(status().isBadRequest());
        close(id, false).andExpect(jsonPath("$.status").value("RECEIVED")).andExpect(jsonPath("$.closedBy").value("Ann Lee"));
    }

    @Test
    void anAnnouncedLpnThatNeedsMoreIsReceivedByItem() throws Exception {
        String id = asn("ASN-1", line("SKU-DRILL", 1, null, null, "VPLT-1"), line("SKU-DRILL", 1, null, null, null));
        checkLpn(id, "VPLT-1").andExpect(jsonPath("$.whyNotAll", containsString("serial numbers")));
        receiveLpn(id, "VPLT-1", UUID.randomUUID()).andExpect(status().isBadRequest());
        receive(id, "VPLT-1", "222000", 1, Map.of("serialNumbers", List.of("D1"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].receivedQuantity").value(1))
                .andExpect(jsonPath("$.lines[1].receivedQuantity").value(0));
        String expired = asn("ASN-2", line("SKU-BAR", 1, "L1", TODAY.minusDays(1), "VPLT-2"));
        receiveLpn(expired, "VPLT-2", UUID.randomUUID()).andExpect(jsonPath("$.errors.expiryDate").exists());
    }

    // ---------- Reversing and closing ----------

    @Test
    void aReceiptCanBeReversedAndDoneAgain() throws Exception {
        String id = asn("ASN-1", line("SKU-CUP", 10));
        String receipt = body(receive(id, "T1", "333000", 4, Map.of())).get("receipts").get(0).get("id").asString();
        call(post("/api/rf/receiving/receipts/" + receipt + "/reverse"), ann).andExpect(status().isOk())
                .andExpect(jsonPath("$.receivedQuantity").value(0))
                .andExpect(jsonPath("$.receipts[0].reversedBy").value("Ann Lee"));
        assertThat(stock("T1")).isEmpty();
        assertThat(container("T1").getStatus()).isEqualTo(ContainerStatus.AVAILABLE);
        call(post("/api/rf/receiving/receipts/" + receipt + "/reverse"), ann).andExpect(status().isConflict());
        receive(id, "T1", "333000", 10, Map.of()).andExpect(status().isOk());
        close(id, false).andExpect(status().isOk());
    }

    @Test
    void movedStockCannotBeReversed() throws Exception {
        String id = asn("ASN-1", line("SKU-CUP", 10));
        String receipt = body(receive(id, "LP1", "333000", 4, Map.of())).get("receipts").get(0).get("id").asString();
        container("LP1").setCurrentLocation(locations.findByLocationId("DC1-BK-01-01-A-01").orElseThrow());
        call(post("/api/rf/receiving/receipts/" + receipt + "/reverse"), ann).andExpect(status().isConflict());
        assertThat(stock("LP1")).hasSize(1);
    }

    @Test
    void closingShortIsConfirmedAndEmptiedLpnsAreRetired() throws Exception {
        String id = asn("ASN-1", line("SKU-CUP", 10));
        close(id, true).andExpect(status().isConflict());
        String receipt = body(receive(id, "LP1", "333000", 4, Map.of())).get("receipts").get(0).get("id").asString();
        receive(id, "LP2", "333000", 3, Map.of()).andExpect(status().isOk());
        call(post("/api/rf/receiving/receipts/" + receipt + "/reverse"), ann).andExpect(status().isOk());
        close(id, false).andExpect(status().isConflict()).andExpect(jsonPath("$.errors.short").value("7"));
        close(id, true).andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.events[*].message", hasItem("Ann Lee closed it: 3 of 10 EA received, 7 short")));
        assertThat(container("LP1").getStatus()).isEqualTo(ContainerStatus.RETIRED);
        assertThat(container("LP2").getStatus()).isEqualTo(ContainerStatus.IN_USE);
        receive(id, "LP2", "333000", 1, Map.of()).andExpect(status().isConflict());
        call(get("/api/rf/receiving/asns"), ann).andExpect(jsonPath("$", hasSize(0)));
    }

    // ---------- Helpers ----------

    @SafeVarargs
    private Map<String, Object> request(String number, Map<String, Object>... lines) {
        Map<String, Object> body = new HashMap<>(Map.of("vendorName", "Acme", "reference", "PO-9", "lines", List.of(lines)));
        body.put("asnNumber", number);
        return body;
    }

    @SafeVarargs
    private String asn(String number, Map<String, Object>... lines) throws Exception {
        return mapper.readTree(call(json(post("/api/asns"), request(number, lines)), null).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asString();
    }

    private static Map<String, Object> line(String item, int quantity) {
        return line(item, quantity, null, null, null);
    }

    private static Map<String, Object> line(String item, int quantity, String lot, LocalDate expiry, String lpn) {
        Map<String, Object> line = new HashMap<>(Map.of("itemId", item, "quantity", quantity));
        line.put("lotNumber", lot);
        line.put("expiryDate", expiry);
        line.put("lpnId", lpn);
        return line;
    }

    private ResultActions blind(UUID requestId, String vendor, String reference) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", requestId);
        body.put("vendorName", vendor);
        body.put("reference", reference);
        return call(json(post("/api/rf/receiving/blind"), body), ann);
    }

    private ResultActions checkLpn(String asnId, String lpn) throws Exception {
        return call(json(post("/api/rf/receiving/asns/" + asnId + "/lpn"), Map.of("locationId", RCV, "lpnId", lpn)), ann);
    }

    private ResultActions receive(String asnId, String lpn, String barcode, int quantity, Map<String, Object> more) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("requestId", UUID.randomUUID(), "locationId", RCV, "lpnId", lpn,
                "containerTypeId", pallet.getId(), "itemBarcode", barcode, "quantity", quantity));
        body.putAll(more);
        return call(json(post("/api/rf/receiving/asns/" + asnId + "/receive"), body), ann);
    }

    private ResultActions receiveLpn(String asnId, String lpn, UUID request) throws Exception {
        return call(json(post("/api/rf/receiving/asns/" + asnId + "/receive-lpn"), Map.of("requestId", request, "locationId", RCV,
                "lpnId", lpn, "containerTypeId", pallet.getId())), ann);
    }

    private ResultActions close(String asnId, boolean acceptShort) throws Exception {
        return call(json(post("/api/rf/receiving/asns/" + asnId + "/close"), Map.of("acceptShort", acceptShort)), ann);
    }

    private List<InventoryRecord> stock(String lpn) {
        return inventory.findByContainer_IdIn(List.of(container(lpn).getId()));
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

    private Location location(String code, LocationType type, String position) {
        boolean storage = type == LocationType.STORAGE;
        Location l = new Location(UUID.randomUUID());
        l.setLocationId(code);
        l.setType(type);
        l.setArea("DC1");
        l.setZone(storage ? "BK" : "RCV");
        l.setAisle(storage ? "01" : null);
        l.setBay(storage ? "01" : null);
        l.setLevel(storage ? "A" : null);
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

    private static Container tote(String code, ContainerType type) {
        Container c = new Container(UUID.randomUUID());
        c.setLpnId(code);
        c.setContainerType(type);
        c.setStatus(ContainerStatus.AVAILABLE);
        c.setPermanent(true);
        return c;
    }
}
