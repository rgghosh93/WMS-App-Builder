package com.wms.masterdata.uom;

import static org.hamcrest.Matchers.containsString;
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
class UomControllerTest {

    static final String BASE_ID = "00000000-0000-0000-0000-0000000000ea";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper mapper;

    @Test
    void startsWithTheBaseUnit() throws Exception {
        mvc.perform(get("/api/uoms"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].uomCode").value("EA"))
                .andExpect(jsonPath("$[0].kind").value("EACH"))
                .andExpect(jsonPath("$[0].base").value(true));
    }

    @Test
    void createsAndListsAUnit() throws Exception {
        create("""
                {"uomCode":" cs ","description":" Case ","kind":"CASE","status":"ACTIVE"}
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.uomCode").value("CS"))
                .andExpect(jsonPath("$.description").value("Case"))
                .andExpect(jsonPath("$.base").value(false));

        mvc.perform(get("/api/uoms"))
                .andExpect(jsonPath("$[0].uomCode").value("EA"))
                .andExpect(jsonPath("$[1].uomCode").value("CS"));
    }

    @Test
    void reservesEachForTheBaseUnit() throws Exception {
        create("""
                {"uomCode":"PC","description":"Piece","kind":"EACH","status":"ACTIVE"}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.kind", containsString("reserved for the base unit")));
    }

    @Test
    void rejectsDuplicateCode() throws Exception {
        create("""
                {"uomCode":"EA","description":"Another each","kind":"OTHER","status":"ACTIVE"}
                """)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors.uomCode").value("already used by another unit of measure"));
    }

    @Test
    void baseUnitOnlyAllowsDescriptionChanges() throws Exception {
        mvc.perform(put("/api/uoms/" + BASE_ID).contentType(MediaType.APPLICATION_JSON).content("""
                        {"uomCode":"EA","description":"Each (single unit)","kind":"EACH","status":"ACTIVE","version":0}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("Each (single unit)"));

        mvc.perform(put("/api/uoms/" + BASE_ID).contentType(MediaType.APPLICATION_JSON).content("""
                        {"uomCode":"PC","description":"Piece","kind":"EACH","status":"ACTIVE","version":1}
                        """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void baseUnitCannotBeDeleted() throws Exception {
        mvc.perform(delete("/api/uoms/" + BASE_ID))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("base unit")));
    }

    @Test
    void deletesAnUnusedUnit() throws Exception {
        String id = mapper.readTree(create("""
                {"uomCode":"BX","description":"Box","kind":"OTHER","status":"ACTIVE"}
                """).andReturn().getResponse().getContentAsString()).get("id").asString();

        mvc.perform(delete("/api/uoms/" + id)).andExpect(status().isNoContent());
        mvc.perform(get("/api/uoms/" + id)).andExpect(status().isNotFound());
    }

    private ResultActions create(String body) throws Exception {
        return mvc.perform(post("/api/uoms").contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
