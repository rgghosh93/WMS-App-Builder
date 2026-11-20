package com.wms.masterdata.container;

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
class ContainerTypeControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper mapper;

    private static final String PALLET = """
            {"containerTypeId":" plt-gma ","description":" GMA pallet ","category":"PALLET","status":"ACTIVE",
             "reusable":true,"width":48,"depth":40,"height":60,"dimensionUom":"IN",
             "tareWeight":40,"tareWeightUom":"LB","maxWeight":2500,"maxWeightUom":"LB",
             "maxVolume":66.667,"maxVolumeUom":"CU_FT"}
            """;

    @Test
    void createsContainerType() throws Exception {
        create(PALLET)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.containerTypeId").value("PLT-GMA"))
                .andExpect(jsonPath("$.description").value("GMA pallet"))
                .andExpect(jsonPath("$.category").value("PALLET"))
                .andExpect(jsonPath("$.reusable").value(true))
                .andExpect(jsonPath("$.tareWeight").value(40))
                .andExpect(jsonPath("$.maxVolumeUom").value("CU_FT"))
                .andExpect(jsonPath("$.version").value(0));
    }

    @Test
    void weightsAndVolumeAreOptionalAndReusableDefaultsToFalse() throws Exception {
        create("""
                {"containerTypeId":"CTN-1","description":"Carton","category":"CARTON","status":"ACTIVE",
                 "width":12,"depth":9,"height":6,"dimensionUom":"IN"}
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reusable").value(false))
                .andExpect(jsonPath("$.tareWeight").doesNotExist())
                .andExpect(jsonPath("$.maxWeight").doesNotExist())
                .andExpect(jsonPath("$.maxVolume").doesNotExist());
    }

    @Test
    void requiresIdDescriptionCategoryAndDimensions() throws Exception {
        create("{\"status\":\"ACTIVE\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.containerTypeId").exists())
                .andExpect(jsonPath("$.errors.description").exists())
                .andExpect(jsonPath("$.errors.category").exists())
                .andExpect(jsonPath("$.errors.width").exists())
                .andExpect(jsonPath("$.errors.dimensionUom").exists());
    }

    @Test
    void requiresUnitsForWeightsAndVolume() throws Exception {
        create("""
                {"containerTypeId":"X","description":"X","category":"TOTE","status":"ACTIVE",
                 "width":1,"depth":1,"height":1,"dimensionUom":"IN","tareWeight":1,"maxWeight":2,"maxVolume":3}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.tareWeightUom").value("required when tare weight is given"))
                .andExpect(jsonPath("$.errors.maxWeightUom").exists())
                .andExpect(jsonPath("$.errors.maxVolumeUom").exists());
    }

    @Test
    void rejectsDuplicateId() throws Exception {
        create(PALLET).andExpect(status().isCreated());
        create(PALLET)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors.containerTypeId").value("already used by another container type"));
    }

    @Test
    void updatesWithVersion() throws Exception {
        String id = mapper.readTree(create(PALLET).andReturn().getResponse().getContentAsString()).get("id").asString();
        String update = """
                {"containerTypeId":"PLT-GMA","description":"GMA pallet, heat treated","category":"PALLET",
                 "status":"INACTIVE","reusable":true,"width":48,"depth":40,"height":72,"dimensionUom":"IN","version":%d}
                """;

        mvc.perform(put("/api/container-types/" + id).contentType(MediaType.APPLICATION_JSON).content(update.formatted(0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"))
                .andExpect(jsonPath("$.height").value(72))
                .andExpect(jsonPath("$.tareWeight").doesNotExist())
                .andExpect(jsonPath("$.version").value(1));

        mvc.perform(put("/api/container-types/" + id).contentType(MediaType.APPLICATION_JSON).content(update.formatted(0)))
                .andExpect(status().isConflict());
    }

    @Test
    void filtersByCategoryAndSearchText() throws Exception {
        create(PALLET);
        create("""
                {"containerTypeId":"TOTE-1","description":"Blue tote","category":"TOTE","status":"ACTIVE",
                 "width":24,"depth":16,"height":12,"dimensionUom":"IN"}
                """);
        create("""
                {"containerTypeId":"TOTE-2","description":"Grey tote","category":"TOTE","status":"INACTIVE",
                 "width":24,"depth":16,"height":12,"dimensionUom":"IN"}
                """);

        mvc.perform(get("/api/container-types").param("category", "TOTE"))
                .andExpect(jsonPath("$.content", hasSize(2)));
        mvc.perform(get("/api/container-types").param("category", "TOTE").param("status", "ACTIVE"))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].containerTypeId").value("TOTE-1"));
        mvc.perform(get("/api/container-types").param("q", "grey"))
                .andExpect(jsonPath("$.content[0].containerTypeId").value("TOTE-2"));
    }

    @Test
    void deletesContainerType() throws Exception {
        String id = mapper.readTree(create(PALLET).andReturn().getResponse().getContentAsString()).get("id").asString();
        mvc.perform(delete("/api/container-types/" + id)).andExpect(status().isNoContent());
        mvc.perform(get("/api/container-types/" + id)).andExpect(status().isNotFound());
    }

    private ResultActions create(String body) throws Exception {
        return mvc.perform(post("/api/container-types").contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
