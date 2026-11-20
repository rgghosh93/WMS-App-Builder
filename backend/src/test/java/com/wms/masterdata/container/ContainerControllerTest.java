package com.wms.masterdata.container;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
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
import com.wms.masterdata.location.Location;
import com.wms.masterdata.location.LocationRepository;
import com.wms.masterdata.location.LocationType;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ContainerControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private ContainerTypeRepository typeRepository;

    @Autowired
    private LocationRepository locationRepository;

    private UUID tote;
    private UUID bin;
    private UUID carton;
    private UUID oldTote;
    private UUID location;

    @BeforeEach
    void fixtures() {
        tote = type("TOTE-STD", ContainerCategory.TOTE, true, ActiveStatus.ACTIVE);
        bin = type("BIN-ASRS", ContainerCategory.BIN, true, ActiveStatus.ACTIVE);
        carton = type("CTN-M", ContainerCategory.CARTON, false, ActiveStatus.ACTIVE);
        oldTote = type("TOTE-OLD", ContainerCategory.TOTE, true, ActiveStatus.INACTIVE);
        location = location("DC1-AS-01-01-A-01");
    }

    @Test
    void createsContainerWithTypeAndLocation() throws Exception {
        create(body(" tote00001 ", tote, "AVAILABLE", location, "  New tote "))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lpnId").value("TOTE00001"))
                .andExpect(jsonPath("$.containerType.containerTypeId").value("TOTE-STD"))
                .andExpect(jsonPath("$.containerType.category").value("TOTE"))
                .andExpect(jsonPath("$.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.currentLocation.locationId").value("DC1-AS-01-01-A-01"))
                .andExpect(jsonPath("$.notes").value("New tote"));
    }

    @Test
    void locationIsOptional() throws Exception {
        create(body("T1", tote, "AVAILABLE", null, null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.currentLocation").doesNotExist());
    }

    @Test
    void rejectsSingleUseAndInactiveTypes() throws Exception {
        create(body("T1", carton, "AVAILABLE", null, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.containerTypeId", containsString("single-use")));
        create(body("T1", oldTote, "AVAILABLE", null, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.containerTypeId", containsString("inactive")));
    }

    @Test
    void keepsInactiveTypeOnExistingContainer() throws Exception {
        String id = id(create(body("T1", tote, "AVAILABLE", null, null)));
        ContainerType type = typeRepository.findById(tote).orElseThrow();
        type.setStatus(ActiveStatus.INACTIVE);
        typeRepository.saveAndFlush(type);

        update(id, body("T1", tote, "ON_HOLD", null, "dirty", 0L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ON_HOLD"));
    }

    @Test
    void rejectsUnknownTypeAndLocation() throws Exception {
        create(body("T1", UUID.randomUUID(), "AVAILABLE", UUID.randomUUID(), null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.containerTypeId").value("container type not found"))
                .andExpect(jsonPath("$.errors.currentLocationId").value("location not found"));
    }

    @Test
    void rejectsDuplicateLpn() throws Exception {
        create(body("T1", tote, "AVAILABLE", null, null)).andExpect(status().isCreated());
        create(body("t1", bin, "AVAILABLE", null, null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors.lpnId").value("already used by another container"));
    }

    @Test
    void updatesWithVersion() throws Exception {
        String id = id(create(body("T1", tote, "AVAILABLE", null, null)));

        update(id, body("T1", bin, "IN_USE", location, null, 0L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.containerType.containerTypeId").value("BIN-ASRS"))
                .andExpect(jsonPath("$.currentLocation.locationId").value("DC1-AS-01-01-A-01"))
                .andExpect(jsonPath("$.version").value(1));
        update(id, body("T1", bin, "IN_USE", null, null, 0L)).andExpect(status().isConflict());
    }

    @Test
    void createsARange() throws Exception {
        mvc.perform(post("/api/containers/range").contentType(MediaType.APPLICATION_JSON).content("""
                        {"prefix":"tote","start":1,"count":50,"digits":5,"containerTypeId":"%s","status":"AVAILABLE"}
                        """.formatted(tote)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.created").value(50))
                .andExpect(jsonPath("$.firstLpnId").value("TOTE00001"))
                .andExpect(jsonPath("$.lastLpnId").value("TOTE00050"));

        mvc.perform(get("/api/containers").param("q", "TOTE000").param("size", "200"))
                .andExpect(jsonPath("$.totalElements").value(50));
    }

    @Test
    void rangeIsAllOrNothing() throws Exception {
        create(body("TOTE00010", tote, "AVAILABLE", null, null));

        mvc.perform(post("/api/containers/range").contentType(MediaType.APPLICATION_JSON).content("""
                        {"prefix":"TOTE","start":1,"count":20,"digits":5,"containerTypeId":"%s","status":"AVAILABLE"}
                        """.formatted(tote)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors.start", containsString("TOTE00010")));
        mvc.perform(get("/api/containers")).andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void rangeChecksDigitsAndCount() throws Exception {
        mvc.perform(post("/api/containers/range").contentType(MediaType.APPLICATION_JSON).content("""
                        {"prefix":"T","start":95,"count":10,"digits":2,"containerTypeId":"%s","status":"AVAILABLE"}
                        """.formatted(tote)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.digits", containsString("3 digits")));
        mvc.perform(post("/api/containers/range").contentType(MediaType.APPLICATION_JSON).content("""
                        {"prefix":"T","start":1,"count":1001,"digits":5,"containerTypeId":"%s","status":"AVAILABLE"}
                        """.formatted(tote)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.count").exists());
    }

    @Test
    void filtersAndSortsByTypeAndLocation() throws Exception {
        create(body("A1", tote, "AVAILABLE", null, null));
        create(body("B1", bin, "IN_USE", location, null));
        create(body("B2", bin, "RETIRED", null, null));

        mvc.perform(get("/api/containers").param("containerTypeId", bin.toString()))
                .andExpect(jsonPath("$.content", hasSize(2)));
        mvc.perform(get("/api/containers").param("status", "IN_USE"))
                .andExpect(jsonPath("$.content[0].lpnId").value("B1"));
        mvc.perform(get("/api/containers").param("locationId", location.toString()))
                .andExpect(jsonPath("$.content", hasSize(1)));
        mvc.perform(get("/api/containers").param("sort", "containerType").param("dir", "desc"))
                .andExpect(jsonPath("$.content[0].containerType.containerTypeId").value("TOTE-STD"));
        mvc.perform(get("/api/containers").param("sort", "location"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(3)));
    }

    @Test
    void deletesContainer() throws Exception {
        String id = id(create(body("T1", tote, "AVAILABLE", null, null)));
        mvc.perform(delete("/api/containers/" + id)).andExpect(status().isNoContent());
        mvc.perform(get("/api/containers/" + id)).andExpect(status().isNotFound());
    }

    @Test
    void blocksDeletingATypeOrLocationInUse() throws Exception {
        create(body("T1", tote, "AVAILABLE", location, null));

        mvc.perform(delete("/api/container-types/" + tote))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("used by 1 container")));
        mvc.perform(delete("/api/locations/" + location))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("1 container is at this location")));
    }

    @Test
    void blocksMakingAUsedTypeSingleUse() throws Exception {
        create(body("T1", tote, "AVAILABLE", null, null));
        ContainerType t = typeRepository.findById(tote).orElseThrow();

        mvc.perform(put("/api/container-types/" + tote).contentType(MediaType.APPLICATION_JSON).content("""
                        {"containerTypeId":"TOTE-STD","description":"Tote","category":"TOTE","status":"ACTIVE",
                         "reusable":false,"width":1,"depth":1,"height":1,"dimensionUom":"IN","version":%d}
                        """.formatted(t.getVersion())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.reusable", containsString("must stay reusable")));
    }

    private UUID type(String code, ContainerCategory category, boolean reusable, ActiveStatus status) {
        ContainerType t = new ContainerType(UUID.randomUUID());
        t.setContainerTypeId(code);
        t.setDescription(code);
        t.setCategory(category);
        t.setStatus(status);
        t.setReusable(reusable);
        t.setWidth(BigDecimal.ONE);
        t.setDepth(BigDecimal.ONE);
        t.setHeight(BigDecimal.ONE);
        t.setDimensionUom(DimensionUom.IN);
        return typeRepository.save(t).getId();
    }

    private UUID location(String locationId) {
        Location l = new Location(UUID.randomUUID());
        l.setLocationId(locationId);
        l.setType(LocationType.STORAGE);
        l.setArea("DC1");
        l.setZone("AS");
        l.setAisle("01");
        l.setBay("01");
        l.setLevel("A");
        l.setPosition("01");
        l.setWidth(BigDecimal.TEN);
        l.setDepth(BigDecimal.TEN);
        l.setHeight(BigDecimal.TEN);
        l.setDimensionUom(DimensionUom.IN);
        return locationRepository.save(l).getId();
    }

    private ResultActions create(String body) throws Exception {
        return mvc.perform(post("/api/containers").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions update(String id, String body) throws Exception {
        return mvc.perform(put("/api/containers/" + id).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String id(ResultActions result) throws Exception {
        return mapper.readTree(result.andReturn().getResponse().getContentAsString()).get("id").asString();
    }

    private static String body(String lpnId, UUID typeId, String status, UUID locationId, String notes) {
        return body(lpnId, typeId, status, locationId, notes, null);
    }

    private static String body(String lpnId, UUID typeId, String status, UUID locationId, String notes, Long version) {
        return """
                {"lpnId":"%s","containerTypeId":"%s","status":"%s","currentLocationId":%s,"notes":%s,"version":%s}
                """.formatted(lpnId, typeId, status,
                locationId == null ? "null" : "\"" + locationId + "\"",
                notes == null ? "null" : "\"" + notes + "\"",
                version);
    }
}
