package com.wms.masterdata.item;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ItemPackagingTest {

    static final String EA = "00000000-0000-0000-0000-0000000000ea";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper mapper;

    private String cs;
    private String pl;
    private String inactive;

    @BeforeEach
    void units() throws Exception {
        cs = uom("CS", "CASE", "ACTIVE");
        pl = uom("PL", "PALLET", "ACTIVE");
        inactive = uom("OLD", "OTHER", "INACTIVE");
    }

    @Test
    void createsItemWithPacksSmallestFirst() throws Exception {
        createItem("SKU-1", "0001", """
                [{"uomId":"%s","quantity":480},
                 {"uomId":"%s","quantity":12,"barcode":"10001","width":18,"depth":12,"height":10,"dimensionUom":"IN",
                  "weight":14.5,"weightUom":"LB"}]""".formatted(pl, cs))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.uoms", hasSize(2)))
                .andExpect(jsonPath("$.uoms[0].uom.uomCode").value("CS"))
                .andExpect(jsonPath("$.uoms[0].quantity").value(12))
                .andExpect(jsonPath("$.uoms[0].barcode").value("10001"))
                .andExpect(jsonPath("$.uoms[0].weight").value(14.5))
                .andExpect(jsonPath("$.uoms[1].uom.uomCode").value("PL"))
                .andExpect(jsonPath("$.uoms[1].quantity").value(480));
    }

    @Test
    void itemsWithoutPacksHaveAnEmptyList() throws Exception {
        createItem("SKU-1", null, null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.uoms", hasSize(0)));
    }

    @Test
    void rejectsBadPacks() throws Exception {
        createItem("SKU-1", null, """
                [{"uomId":"%s","quantity":12},
                 {"uomId":"%s","quantity":24},
                 {"uomId":"%s","quantity":1},
                 {"uomId":"%s","quantity":5},
                 {"uomId":"%s","quantity":10,"width":5}]""".formatted(EA, cs, pl, inactive, UUID.randomUUID()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['uoms[2].quantity']").value("must be at least 2 eaches"));

        createItem("SKU-1", null, """
                [{"uomId":"%s","quantity":12},
                 {"uomId":"%s","quantity":24},
                 {"uomId":"%s","quantity":24},
                 {"uomId":"%s","quantity":5},
                 {"uomId":"%s","quantity":10,"width":5}]""".formatted(EA, cs, cs, inactive, UUID.randomUUID()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['uoms[0].uomId']", containsString("base unit")))
                .andExpect(jsonPath("$.errors['uoms[2].uomId']", containsString("listed twice")))
                .andExpect(jsonPath("$.errors['uoms[3].uomId']", containsString("inactive")))
                .andExpect(jsonPath("$.errors['uoms[4].uomId']").value("unit of measure not found"))
                .andExpect(jsonPath("$.errors['uoms[4].depth']").exists());
    }

    @Test
    void packBarcodesMustBeUniqueAcrossItems() throws Exception {
        createItem("SKU-1", "0001", "[{\"uomId\":\"%s\",\"quantity\":12,\"barcode\":\"10001\"}]".formatted(cs))
                .andExpect(status().isCreated());

        createItem("SKU-2", "10001", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors.barcode").value("already assigned to CS of item SKU-1"));
        createItem("SKU-2", "0002", "[{\"uomId\":\"%s\",\"quantity\":6,\"barcode\":\"0001\"}]".formatted(cs))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors['uoms[0].barcode']").value("already assigned to item SKU-1"));
        createItem("SKU-2", "0002", "[{\"uomId\":\"%s\",\"quantity\":6,\"barcode\":\"0002\"}]".formatted(cs))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors['uoms[0].barcode']").value("same as the item's own barcode"));
    }

    @Test
    void updateReplacesPacksAndBumpsVersion() throws Exception {
        JsonNode created = json(createItem("SKU-1", null, """
                [{"uomId":"%s","quantity":12,"barcode":"10001"},{"uomId":"%s","quantity":480}]""".formatted(cs, pl)));
        String id = created.get("id").asString();

        update(id, 0, "[{\"uomId\":\"%s\",\"quantity\":24,\"barcode\":\"10001\"}]".formatted(cs))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uoms", hasSize(1)))
                .andExpect(jsonPath("$.uoms[0].quantity").value(24))
                .andExpect(jsonPath("$.uoms[0].id").value(created.get("uoms").get(0).get("id").asString()))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void leavingOutPacksOnUpdateKeepsThem() throws Exception {
        String id = json(createItem("SKU-1", null, "[{\"uomId\":\"%s\",\"quantity\":12}]".formatted(cs))).get("id").asString();

        update(id, 0, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uoms", hasSize(1)));
    }

    @Test
    void blocksDeletingAUnitItemsUse() throws Exception {
        createItem("SKU-1", null, "[{\"uomId\":\"%s\",\"quantity\":12}]".formatted(cs)).andExpect(status().isCreated());

        mvc.perform(delete("/api/uoms/" + cs))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("1 item uses it")));
    }

    @Test
    void deletingAnItemRemovesItsPacks() throws Exception {
        String id = json(createItem("SKU-1", null, "[{\"uomId\":\"%s\",\"quantity\":12}]".formatted(cs))).get("id").asString();

        mvc.perform(delete("/api/items/" + id)).andExpect(status().isNoContent());
        mvc.perform(delete("/api/uoms/" + cs)).andExpect(status().isNoContent());
    }

    @Test
    void listIncludesPacks() throws Exception {
        createItem("SKU-1", null, "[{\"uomId\":\"%s\",\"quantity\":12}]".formatted(cs));
        createItem("SKU-2", null, null);

        mvc.perform(get("/api/items"))
                .andExpect(jsonPath("$.content[0].uoms[0].uom.uomCode").value("CS"))
                .andExpect(jsonPath("$.content[1].uoms", hasSize(0)));
    }

    private String uom(String code, String kind, String status) throws Exception {
        return json(mvc.perform(post("/api/uoms").contentType(MediaType.APPLICATION_JSON).content("""
                {"uomCode":"%s","description":"%s","kind":"%s","status":"%s"}
                """.formatted(code, code, kind, status)))).get("id").asString();
    }

    private ResultActions createItem(String itemId, String barcode, String uomsJson) throws Exception {
        return mvc.perform(post("/api/items").contentType(MediaType.APPLICATION_JSON).content("""
                {"itemId":"%s","description":"Test","status":"ACTIVE","barcode":%s,"uoms":%s}
                """.formatted(itemId, barcode == null ? "null" : "\"" + barcode + "\"", uomsJson == null ? "null" : uomsJson)));
    }

    private ResultActions update(String id, long version, String uomsJson) throws Exception {
        return mvc.perform(put("/api/items/" + id).contentType(MediaType.APPLICATION_JSON).content("""
                {"itemId":"SKU-1","description":"Test","status":"ACTIVE","uoms":%s,"version":%d}
                """.formatted(uomsJson == null ? "null" : uomsJson, version)));
    }

    private JsonNode json(ResultActions result) throws Exception {
        return mapper.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
