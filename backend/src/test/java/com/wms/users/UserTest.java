package com.wms.users;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UserTest {

    private static final String PIN = "8264";
    private static final String OTHER_PIN = "730519";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private EntityManager em;
    @Autowired private AppUserRepository users;

    @Test
    void aNewUserCanSignIn() throws Exception {
        JsonNode ann = create("ann", "Ann Lee", PIN);
        assertThat(ann.get("userCode").asString()).isEqualTo("ANN");
        assertThat(ann.get("lastSignInAt").isNull()).isTrue();
        signIn("ann", PIN).andExpect(status().isOk());
        call(get("/api/users/" + id(ann))).andExpect(jsonPath("$.devices").value(1)).andExpect(jsonPath("$.lastSignInAt").exists())
                .andExpect(jsonPath("$.inHand", hasSize(0)));
    }

    @Test
    void pinsAreNeverSentBackAndAreStoredHashed() throws Exception {
        create("ANN", "Ann Lee", PIN);
        call(get("/api/users")).andExpect(content().string(not(containsString(PIN)))).andExpect(content().string(not(containsString("pbkdf2"))));
        assertThat(users.findByUserCode("ANN").orElseThrow().getPinHash()).startsWith("pbkdf2$");
    }

    @Test
    void weakPinsAreRefused() throws Exception {
        for (String pin : List.of("", "123", "12a4", "1111", "1234", "9876", "1234567890123", " 8264")) {
            createRaw("ANN", "Ann Lee", pin).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.pin").exists());
        }
        createRaw("ANN", "Ann Lee", null).andExpect(status().isBadRequest());
        createRaw("ANN", "Ann Lee", "1357").andExpect(status().isCreated());
    }

    @Test
    void usersAreFoundAndChecked() throws Exception {
        create("ANN", "Ann Lee", PIN);
        create("BOB", "Bob Ray", PIN);
        for (String q : List.of("ann", "lee")) {
            call(get("/api/users").param("q", q)).andExpect(jsonPath("$.totalElements").value(1));
        }
        call(get("/api/users").param("q", "%")).andExpect(jsonPath("$.totalElements").value(0));
        call(get("/api/users").param("sort", "lastSignInAt").param("dir", "desc")).andExpect(jsonPath("$.totalElements").value(2));
        call(get("/api/users").param("status", "INACTIVE")).andExpect(jsonPath("$.totalElements").value(0));
        call(get("/api/users").param("sort", "pinHash")).andExpect(status().isBadRequest());
        createRaw("ann", "Again", PIN).andExpect(status().isConflict()).andExpect(jsonPath("$.errors.userCode").exists());
        createRaw("a b", " ", PIN).andExpect(jsonPath("$.errors.userCode").exists()).andExpect(jsonPath("$.errors.displayName").exists());
        call(get("/api/users/" + UUID.randomUUID())).andExpect(status().isNotFound());
    }

    @Test
    void makingSomeoneInactiveSignsThemOut() throws Exception {
        JsonNode ann = create("ANN", "Ann Lee", PIN);
        Cookie cookie = signIn("ANN", PIN).andReturn().getResponse().getCookie("wms_device");
        call(get("/api/rf/picking").cookie(cookie)).andExpect(status().isOk());
        // Signing in since the form was opened is not a change to the user.
        update(ann, "ANN", "Ann Leigh", "INACTIVE").andExpect(status().isOk()).andExpect(jsonPath("$.devices").value(0))
                .andExpect(jsonPath("$.displayName").value("Ann Leigh"));
        call(get("/api/rf/picking").cookie(cookie)).andExpect(status().isUnauthorized());
        signIn("ANN", PIN).andExpect(status().isUnauthorized());
        update(ann, "ANN", "Old", "ACTIVE").andExpect(status().isConflict());
        JsonNode now = body(call(get("/api/users/" + id(ann))));
        update(now, "ANN", "Ann Lee", "ACTIVE").andExpect(status().isOk());
        call(get("/api/rf/picking").cookie(cookie)).andExpect(status().isUnauthorized());
        signIn("ANN", PIN).andExpect(status().isOk());
    }

    @Test
    void aNewPinReplacesTheOldOneAtOnce() throws Exception {
        JsonNode ann = create("ANN", "Ann Lee", PIN);
        Cookie cookie = signIn("ANN", PIN).andReturn().getResponse().getCookie("wms_device");
        call(json(put("/api/users/" + id(ann) + "/pin"), Map.of("pin", "1111"))).andExpect(status().isBadRequest());
        call(get("/api/rf/picking").cookie(cookie)).andExpect(status().isOk());
        call(json(put("/api/users/" + id(ann) + "/pin"), Map.of("pin", OTHER_PIN))).andExpect(status().isOk());
        call(get("/api/rf/picking").cookie(cookie)).andExpect(status().isUnauthorized());
        signIn("ANN", PIN).andExpect(status().isUnauthorized());
        signIn("ANN", OTHER_PIN).andExpect(status().isOk());
    }

    @Test
    void aLockedOutUserCanBeLetBackIn() throws Exception {
        JsonNode ann = create("ANN", "Ann Lee", PIN);
        for (int i = 0; i < 5; i++) {
            signIn("ANN", "0001").andExpect(status().isUnauthorized());
        }
        signIn("ANN", PIN).andExpect(status().isTooManyRequests());
        call(get("/api/users")).andExpect(jsonPath("$.content[0].lockedUntil").exists());
        call(json(post("/api/users/" + id(ann) + "/unlock"), Map.of())).andExpect(jsonPath("$.lockedUntil").doesNotExist());
        signIn("ANN", PIN).andExpect(status().isOk());
    }

    @Test
    void someoneCanBeSignedOutEverywhere() throws Exception {
        JsonNode ann = create("ANN", "Ann Lee", PIN);
        Cookie first = signIn("ANN", PIN).andReturn().getResponse().getCookie("wms_device");
        signIn("ANN", PIN);
        call(json(post("/api/users/" + id(ann) + "/sign-out"), Map.of())).andExpect(jsonPath("$.devices").value(0));
        call(get("/api/rf/picking").cookie(first)).andExpect(status().isUnauthorized());
    }

    @Test
    void aUserNeedsAPinOrAPassword() throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("userCode", "ANN", "displayName", "Ann Lee", "status", "ACTIVE"));
        call(json(post("/api/users"), body)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.pin").exists());
        body.put("password", "short");
        call(json(post("/api/users"), body)).andExpect(jsonPath("$.errors.password").exists());
        body.put("password", "a long enough one");
        body.put("roleIds", List.of(UUID.randomUUID()));
        call(json(post("/api/users"), body)).andExpect(jsonPath("$.errors.roleIds").exists());
        body.put("roleIds", List.of(Role.ADMINISTRATOR, Role.OPERATOR));
        call(json(post("/api/users"), body)).andExpect(status().isCreated()).andExpect(jsonPath("$.hasPin").value(false))
                .andExpect(jsonPath("$.hasPassword").value(true)).andExpect(jsonPath("$.roles[*].name", contains("Administrator", "Operator")));
        // No PIN, so no devices, whatever is tried.
        signIn("ANN", "decoy").andExpect(status().isUnauthorized());
    }

    @Test
    void someoneWithoutADeviceRoleCannotUseDevices() throws Exception {
        JsonNode ann = create("ANN", "Ann Lee", PIN);
        Cookie cookie = signIn("ANN", PIN).andReturn().getResponse().getCookie("wms_device");
        call(json(put("/api/users/" + id(ann)), Map.of("userCode", "ANN", "displayName", "Ann Lee", "status", "ACTIVE", "roleIds", List.of(),
                "version", ann.get("version").asLong()))).andExpect(status().isOk());
        call(get("/api/rf/picking").cookie(cookie)).andExpect(status().isForbidden());
        signIn("ANN", PIN).andExpect(status().isForbidden());
    }

    @Test
    void someoneWhoNeverWorkedCanBeDeleted() throws Exception {
        JsonNode ann = create("ANN", "Ann Lee", PIN);
        signIn("ANN", PIN);
        call(delete("/api/users/" + id(ann))).andExpect(status().isNoContent());
        call(get("/api/users/" + id(ann))).andExpect(status().isNotFound());
    }

    private ResultActions createRaw(String code, String name, String pin) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("userCode", code, "displayName", name, "status", "ACTIVE", "roleIds", List.of(Role.OPERATOR)));
        body.put("pin", pin);
        return call(json(post("/api/users"), body));
    }

    private JsonNode create(String code, String name, String pin) throws Exception {
        return mapper.readTree(createRaw(code, name, pin).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private ResultActions update(JsonNode user, String code, String name, String status) throws Exception {
        return call(json(put("/api/users/" + id(user)), Map.of("userCode", code, "displayName", name, "status", status,
                "version", user.get("version").asLong())));
    }

    private ResultActions signIn(String code, String pin) throws Exception {
        return call(json(post("/api/rf/session"), Map.of("userCode", code, "pin", pin)));
    }

    private static String id(JsonNode user) {
        return user.get("id").asString();
    }

    private ResultActions call(MockHttpServletRequestBuilder request) throws Exception {
        em.flush();
        em.clear();
        return mvc.perform(request);
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, Object body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
    }

    private JsonNode body(ResultActions result) throws Exception {
        return mapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
}
