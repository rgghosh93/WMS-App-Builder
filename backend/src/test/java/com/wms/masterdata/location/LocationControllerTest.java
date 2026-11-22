package com.wms.masterdata.location;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class LocationControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper mapper;

    @Test
    void createsStorageLocationWithNormalizedCodes() throws Exception {
        create(body("rs-01-01-a-01", "dc1", "rs", "01", "01", "a", "01", "48", "40", "60"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/locations/")))
                .andExpect(jsonPath("$.locationId").value("RS-01-01-A-01"))
                .andExpect(jsonPath("$.type").value("STORAGE"))
                .andExpect(jsonPath("$.area").value("DC1"))
                .andExpect(jsonPath("$.level").value("A"))
                .andExpect(jsonPath("$.width").value(48))
                .andExpect(jsonPath("$.dimensionUom").value("IN"))
                .andExpect(jsonPath("$.version").value(0));
    }

    @Test
    void requiresEveryStorageIdentifier() throws Exception {
        create(body("X1", "DC1", "RS", " ", "01", "A", "01", "1", "1", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.aisle").value("required for Storage locations"));
    }

    @Test
    void rejectsNonPositiveDimensions() throws Exception {
        create(body("X1", "DC1", "RS", "01", "01", "A", "01", "0", "1", "-2"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.width").exists())
                .andExpect(jsonPath("$.errors.height").exists());
    }

    @Test
    void rejectsInvalidCodeCharacters() throws Exception {
        create(body("X 1", "DC1", "RS", "01", "01", "A", "01", "1", "1", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.locationId").exists());
    }

    @Test
    void rejectsDuplicateLocationId() throws Exception {
        create(body("L1", "DC1", "RS", "01", "01", "A", "01", "1", "1", "1")).andExpect(status().isCreated());

        create(body("l1", "DC1", "RS", "01", "01", "A", "02", "1", "1", "1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors.locationId").value("already used by another location"));
    }

    @Test
    void rejectsDuplicateIdentifierCombination() throws Exception {
        create(body("L1", "DC1", "RS", "01", "01", "A", "01", "1", "1", "1")).andExpect(status().isCreated());

        create(body("L2", "dc1", "rs", "01", "01", "a", "01", "1", "1", "1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors.identifiers", containsString("already location L1")));
    }

    @Test
    void updatesWithCurrentVersionAndRejectsStaleVersion() throws Exception {
        JsonNode created = json(create(body("L1", "DC1", "RS", "01", "01", "A", "01", "1", "1", "1")));
        String id = created.get("id").asString();

        mvc.perform(put("/api/locations/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content(body("L1", "DC1", "RS", "01", "01", "B", "01", "2", "2", "2", 0L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.level").value("B"))
                .andExpect(jsonPath("$.width").value(2))
                .andExpect(jsonPath("$.version").value(1));

        mvc.perform(put("/api/locations/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content(body("L1", "DC1", "RS", "01", "01", "C", "01", "2", "2", "2", 0L)))
                .andExpect(status().isConflict());
    }

    @Test
    void updateCanKeepItsOwnIdentifiers() throws Exception {
        JsonNode created = json(create(body("L1", "DC1", "RS", "01", "01", "A", "01", "1", "1", "1")));

        mvc.perform(put("/api/locations/" + created.get("id").asString()).contentType(MediaType.APPLICATION_JSON)
                        .content(body("L1", "DC1", "RS", "01", "01", "A", "01", "5", "5", "5", 0L)))
                .andExpect(status().isOk());
    }

    @Test
    void searchesFiltersSortsAndPages() throws Exception {
        create(body("RS-01-01-A-01", "DC1", "RS", "01", "01", "A", "01", "1", "1", "1"));
        create(body("RS-01-02-A-01", "DC1", "RS", "01", "02", "A", "01", "1", "1", "1"));
        create(body("BK-01-01-A-01", "DC1", "BK", "01", "01", "A", "01", "1", "1", "1"));

        mvc.perform(get("/api/locations").param("zone", "rs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));

        mvc.perform(get("/api/locations").param("q", "bk-01"))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].locationId").value("BK-01-01-A-01"));

        mvc.perform(get("/api/locations").param("sort", "locationId").param("dir", "desc").param("size", "2"))
                .andExpect(jsonPath("$.content[0].locationId").value("RS-01-02-A-01"))
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void rejectsUnknownSortField() throws Exception {
        mvc.perform(get("/api/locations").param("sort", "password"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deletesLocation() throws Exception {
        String id = json(create(body("L1", "DC1", "RS", "01", "01", "A", "01", "1", "1", "1"))).get("id").asString();

        mvc.perform(delete("/api/locations/" + id)).andExpect(status().isNoContent());
        mvc.perform(get("/api/locations/" + id)).andExpect(status().isNotFound());
    }

    @Test
    void listsLocationTypesWithRequiredIdentifiers() throws Exception {
        mvc.perform(get("/api/locations/types"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("STORAGE"))
                .andExpect(jsonPath("$[0].label").value("Storage"))
                .andExpect(jsonPath("$[0].requiredIdentifiers", hasSize(6)));
    }

    @Test
    void packStationsAreIdentifiedByAreaZoneAndPosition() throws Exception {
        String station = """
                {"locationId":"%s","type":"PACKING","area":"DC1","zone":"PACK","position":"01"%s,
                 "width":72,"depth":36,"height":36,"dimensionUom":"IN"}
                """;
        create(station.formatted("DC1-PACK-01", "")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("PACKING"))
                .andExpect(jsonPath("$.aisle").value(org.hamcrest.Matchers.nullValue()));
        create(station.formatted("OTHER", "")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors.identifiers", containsString("DC1-PACK-01")));
        create(station.formatted("DC1-PACK-09", ",\"aisle\":\"01\"")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.aisle", containsString("not used")));
    }

    @Test
    void storesCapacityLimits() throws Exception {
        create(withCapacity("""
                "maxWeight":2500,"maxWeightUom":"LB","maxVolume":66.667,"maxVolumeUom":"CU_FT",
                "maxLpns":1,"maxSkus":1,"maxUnits":400"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.maxWeight").value(2500))
                .andExpect(jsonPath("$.maxWeightUom").value("LB"))
                .andExpect(jsonPath("$.maxVolume").value(66.667))
                .andExpect(jsonPath("$.maxVolumeUom").value("CU_FT"))
                .andExpect(jsonPath("$.maxLpns").value(1))
                .andExpect(jsonPath("$.maxSkus").value(1))
                .andExpect(jsonPath("$.maxUnits").value(400));
    }

    @Test
    void capacityIsOptional() throws Exception {
        create(body("L1", "DC1", "RS", "01", "01", "A", "01", "1", "1", "1"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.maxWeight").doesNotExist())
                .andExpect(jsonPath("$.maxVolume").doesNotExist())
                .andExpect(jsonPath("$.maxLpns").doesNotExist());
    }

    @Test
    void capacityNeedsUnitsAndPositiveValues() throws Exception {
        create(withCapacity("\"maxWeight\":10,\"maxVolume\":5"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.maxWeightUom").value("required when max weight is given"))
                .andExpect(jsonPath("$.errors.maxVolumeUom").value("required when max volume is given"));

        create(withCapacity("\"maxWeight\":0,\"maxWeightUom\":\"LB\",\"maxLpns\":-1,\"maxSkus\":0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.maxWeight").exists())
                .andExpect(jsonPath("$.errors.maxLpns").exists())
                .andExpect(jsonPath("$.errors.maxSkus").exists());
    }

    @Test
    void ignoresUnitWithoutValue() throws Exception {
        create(withCapacity("\"maxWeightUom\":\"KG\",\"maxVolumeUom\":\"L\""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.maxWeightUom").doesNotExist())
                .andExpect(jsonPath("$.maxVolumeUom").doesNotExist());
    }

    private static String withCapacity(String capacityJson) {
        return """
                {"locationId":"CAP-1","type":"STORAGE","area":"DC1","zone":"RS","aisle":"09","bay":"01",
                 "level":"A","position":"01","width":48,"depth":40,"height":60,"dimensionUom":"IN",%s}
                """.formatted(capacityJson);
    }

    private ResultActions create(String body) throws Exception {
        return mvc.perform(post("/api/locations").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private JsonNode json(ResultActions result) throws Exception {
        return mapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private static String body(String locationId, String area, String zone, String aisle, String bay,
                               String level, String position, String width, String depth, String height) {
        return body(locationId, area, zone, aisle, bay, level, position, width, depth, height, null);
    }

    private static String body(String locationId, String area, String zone, String aisle, String bay,
                               String level, String position, String width, String depth, String height, Long version) {
        return """
                {"locationId":"%s","type":"STORAGE","area":"%s","zone":"%s","aisle":"%s","bay":"%s",
                 "level":"%s","position":"%s","width":%s,"depth":%s,"height":%s,"dimensionUom":"IN","version":%s}
                """.formatted(locationId, area, zone, aisle, bay, level, position, width, depth, height, version);
    }
}
