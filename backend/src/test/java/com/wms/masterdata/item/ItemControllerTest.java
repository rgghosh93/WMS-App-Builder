package com.wms.masterdata.item;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ItemControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper mapper;

    private static final String FULL = """
            {"itemId":" sku-1 ","description":"  Cordless drill ","status":"ACTIVE","barcode":"0012345",
             "lotControlled":true,"serialControlled":false,"expiryControlled":true,"shelfLifeDays":365,
             "width":12,"depth":4,"height":10.5,"dimensionUom":"IN","weight":4.25,"weightUom":"LB"}
            """;

    @Test
    void createsItemWithAllAttributes() throws Exception {
        create(FULL)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.itemId").value("SKU-1"))
                .andExpect(jsonPath("$.description").value("Cordless drill"))
                .andExpect(jsonPath("$.barcode").value("0012345"))
                .andExpect(jsonPath("$.lotControlled").value(true))
                .andExpect(jsonPath("$.shelfLifeDays").value(365))
                .andExpect(jsonPath("$.height").value(10.5))
                .andExpect(jsonPath("$.weight").value(4.25))
                .andExpect(jsonPath("$.weightUom").value("LB"))
                .andExpect(jsonPath("$.version").value(0));
    }

    @Test
    void createsMinimalItem() throws Exception {
        create("""
                {"itemId":"SKU-2","description":"Tote","status":"ACTIVE"}
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.barcode").doesNotExist())
                .andExpect(jsonPath("$.width").doesNotExist())
                .andExpect(jsonPath("$.lotControlled").value(false));
    }

    @Test
    void requiresIdDescriptionAndStatus() throws Exception {
        create("{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.itemId").exists())
                .andExpect(jsonPath("$.errors.description").exists())
                .andExpect(jsonPath("$.errors.status").exists());
    }

    @Test
    void requiresAllThreeDimensionsTogether() throws Exception {
        create("""
                {"itemId":"SKU-3","description":"Box","status":"ACTIVE","width":5}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.depth").value("required when any dimension is given"))
                .andExpect(jsonPath("$.errors.height").exists())
                .andExpect(jsonPath("$.errors.dimensionUom").exists());
    }

    @Test
    void requiresWeightUnit() throws Exception {
        create("""
                {"itemId":"SKU-3","description":"Box","status":"ACTIVE","weight":2}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.weightUom").exists());
    }

    @Test
    void rejectsShelfLifeWithoutExpiryTracking() throws Exception {
        create("""
                {"itemId":"SKU-3","description":"Box","status":"ACTIVE","expiryControlled":false,"shelfLifeDays":30}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.shelfLifeDays").value("only used when expiry dates are tracked"));
    }

    @Test
    void rejectsDuplicateItemIdAndBarcode() throws Exception {
        create(FULL).andExpect(status().isCreated());

        create("""
                {"itemId":"SKU-1","description":"Other","status":"ACTIVE","barcode":"0012345"}
                """)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors.itemId").value("already used by another item"))
                .andExpect(jsonPath("$.errors.barcode").value("already assigned to item SKU-1"));
    }

    @Test
    void updatesWithVersionAndClearsOptionalGroups() throws Exception {
        String id = mapper.readTree(create(FULL).andReturn().getResponse().getContentAsString()).get("id").asString();

        mvc.perform(put("/api/items/" + id).contentType(MediaType.APPLICATION_JSON).content("""
                        {"itemId":"SKU-1","description":"Cordless drill","status":"INACTIVE","barcode":"0012345","version":0}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"))
                .andExpect(jsonPath("$.width").doesNotExist())
                .andExpect(jsonPath("$.weight").doesNotExist())
                .andExpect(jsonPath("$.shelfLifeDays").doesNotExist())
                .andExpect(jsonPath("$.version").value(1));

        mvc.perform(put("/api/items/" + id).contentType(MediaType.APPLICATION_JSON).content("""
                        {"itemId":"SKU-1","description":"x","status":"ACTIVE","version":0}
                        """))
                .andExpect(status().isConflict());
    }

    @Test
    void searchesAndFiltersByStatus() throws Exception {
        create("""
                {"itemId":"SKU-A","description":"Blue widget","status":"ACTIVE","barcode":"111"}
                """);
        create("""
                {"itemId":"SKU-B","description":"Red widget","status":"INACTIVE"}
                """);
        create("""
                {"itemId":"SKU-C","description":"Gadget","status":"ACTIVE"}
                """);

        mvc.perform(get("/api/items").param("q", "widget"))
                .andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(get("/api/items").param("q", "111"))
                .andExpect(jsonPath("$.content[0].itemId").value("SKU-A"));
        mvc.perform(get("/api/items").param("status", "ACTIVE").param("sort", "description"))
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.content[0].description").value("Blue widget"));
    }

    @Test
    void deletesItem() throws Exception {
        String id = mapper.readTree(create(FULL).andReturn().getResponse().getContentAsString()).get("id").asString();

        mvc.perform(delete("/api/items/" + id)).andExpect(status().isNoContent());
        mvc.perform(get("/api/items/" + id)).andExpect(status().isNotFound());
    }

    private ResultActions create(String body) throws Exception {
        return mvc.perform(post("/api/items").contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
