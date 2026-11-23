package com.wms.users;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Signing in to the desktop, and who may do what. Unlike other tests, nobody is signed in to start with. */
@SpringBootTest
@Transactional
class AccessTest {

    private static final String PASSWORD = "correct horse battery";
    private static final String OTHER = "staple another one";

    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper mapper;
    @Autowired private EntityManager em;
    @Autowired private AppUserRepository users;
    @Autowired private TestUsers people;
    @Autowired @Qualifier("requestMappingHandlerMapping") private RequestMappingHandlerMapping mappings;

    private MockMvc mvc;

    @BeforeEach
    void nobodySignedIn() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    // ---------- Everything is covered ----------

    @Test
    void everyChangeAndEveryDeviceRequestNamesAPermission() {
        List<String> open = new ArrayList<>();
        mappings.getHandlerMethods().forEach((info, method) -> {
            for (String path : info.getPatternValues()) {
                if (!path.startsWith("/api/") || path.startsWith("/api/session") || path.startsWith("/api/rf/session")) {
                    continue;
                }
                Requires requires = AccessConfig.required(method);
                boolean device = path.startsWith("/api/rf/");
                boolean reading = info.getMethodsCondition().getMethods().equals(Set.of(RequestMethod.GET));
                if (requires == null ? device || !reading
                        : requires.value().group() != (device ? Permission.Group.DEVICE : Permission.Group.DESKTOP)) {
                    open.add(info.getMethodsCondition().getMethods() + " " + path);
                }
            }
        });
        assertThat(open).isEmpty();
        assertThat(mappings.getHandlerMethods()).hasSizeGreaterThan(80);
    }

    @Test
    void nothingIsSeenWithoutSigningIn() throws Exception {
        for (String path : List.of("/api/orders", "/api/navigation/modules", "/api/items", "/api/users", "/api/roles", "/api/inventory/locations",
                "/api/rf/picking", "/api/nothing")) {
            call(get(path), null).andExpect(status().isUnauthorized());
        }
        call(json(post("/api/orders"), Map.of()), null).andExpect(status().isUnauthorized());
        // Other spellings of the same path don't get around it.
        call(get(java.net.URI.create("/api/%6Frders")), null).andExpect(status().isUnauthorized());
        call(get(java.net.URI.create("/api/session/../orders")), null).andExpect(r -> assertThat(r.getResponse().getStatus()).isBetween(400, 499));
    }

    // ---------- The first administrator ----------

    @Test
    void theFirstAdministratorIsMadeOnThisComputer() throws Exception {
        call(get("/api/session"), null).andExpect(jsonPath("$.state").value("SETUP")).andExpect(jsonPath("$.setupHere").value(true));
        Map<String, Object> boss = Map.of("userCode", "boss", "displayName", "The Boss", "password", PASSWORD);
        call(json(post("/api/session/setup"), boss).with(r -> {
            r.setRemoteAddr("192.168.1.20");
            return r;
        }), null).andExpect(status().isForbidden());
        for (String forwarded : List.of("192.168.1.20", "127.0.0.1, 192.168.1.20", "localhost")) {
            call(json(post("/api/session/setup"), boss).header("X-Forwarded-For", forwarded), null).andExpect(status().isForbidden());
        }
        call(json(post("/api/session/setup"), boss).with(r -> {
            r.setServerName("evil.example");
            return r;
        }), null).andExpect(status().isForbidden());
        call(get("/api/session").header("X-Forwarded-For", "192.168.1.20"), null).andExpect(jsonPath("$.setupHere").value(false));

        Cookie cookie = cookie(call(json(post("/api/session/setup"), boss).header("X-Forwarded-For", "::ffff:127.0.0.1"), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.permissions", hasItem("USERS"))));
        call(get("/api/session"), cookie).andExpect(jsonPath("$.state").value("SIGNED_IN")).andExpect(jsonPath("$.user.userCode").value("BOSS"));
        call(get("/api/users"), cookie).andExpect(status().isOk());
        call(get("/api/session"), null).andExpect(jsonPath("$.state").value("SIGNED_OUT"));
        call(json(post("/api/session/setup"), Map.of("userCode", "two", "displayName", "Two", "password", PASSWORD)), null)
                .andExpect(status().isConflict());
    }

    @Test
    void setupRefusesWeakPasswordsAndTakenIds() throws Exception {
        people.operator("PICKER", "Picker", "8264");
        for (String weak : List.of("short", "aaaaaaaaaaaa", "Password123!", "1234567890", "boss is great 1")) {
            call(json(post("/api/session/setup"), Map.of("userCode", "BOSS", "displayName", "B", "password", weak)), null)
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.password").exists());
        }
        call(json(post("/api/session/setup"), Map.of("userCode", "PICKER", "displayName", "B", "password", PASSWORD)), null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errors.userCode").exists());
    }

    // ---------- Signing in ----------

    @Test
    void wrongPasswordsAreRefusedThenLockedOut() throws Exception {
        boss();
        signIn("NOBODY", PASSWORD).andExpect(status().isUnauthorized());
        for (int i = 0; i < 5; i++) {
            signIn("BOSS", "wrong").andExpect(status().isUnauthorized()).andExpect(jsonPath("$.detail").value("User ID or password is not right"));
        }
        signIn("BOSS", PASSWORD).andExpect(status().isTooManyRequests());
    }

    @Test
    void theCookieCannotBeReadOrSentByOthersAndSigningOutEndsIt() throws Exception {
        Cookie cookie = boss();
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getAttribute("SameSite")).isEqualTo("Strict");
        assertThat(cookie.getPath()).isEqualTo("/api");
        call(delete("/api/session"), cookie).andExpect(status().isNoContent());
        call(get("/api/orders"), cookie).andExpect(status().isUnauthorized());
    }

    @Test
    void changesAskedForByOtherSitesAreRefused() throws Exception {
        Cookie cookie = boss();
        for (String site : List.of("cross-site", "same-site")) {
            call(post("/api/orders/" + UUID.randomUUID() + "/cancel").header("Sec-Fetch-Site", site), cookie).andExpect(status().isForbidden());
            signInRequest("BOSS", PASSWORD, site).andExpect(status().isForbidden());
        }
        call(post("/api/orders/" + UUID.randomUUID() + "/cancel").header("Sec-Fetch-Site", "same-origin"), cookie).andExpect(status().isNotFound());
    }

    @Test
    void deviceAndDesktopSessionsAreSeparate() throws Exception {
        Cookie desktop = boss();
        people.operator("ANN", "Ann Lee", "8264");
        Cookie device = cookieNamed(call(json(post("/api/rf/session"), Map.of("userCode", "ANN", "pin", "8264")), null), "wms_device");
        call(get("/api/rf/picking"), device).andExpect(status().isOk());
        call(get("/api/orders"), device).andExpect(status().isUnauthorized());
        call(get("/api/orders"), new Cookie("wms_desktop", device.getValue())).andExpect(status().isUnauthorized());
        call(get("/api/rf/picking"), new Cookie("wms_device", desktop.getValue())).andExpect(status().isUnauthorized());
        signIn("ANN", "8264").andExpect(status().isUnauthorized());
    }

    @Test
    void aPasswordSomeoneElseSetOnlyServesToChooseOnesOwn() throws Exception {
        Cookie boss = boss();
        user(boss, "VIC", PASSWORD, "Viewer");
        Cookie vic = cookie(signIn("VIC", PASSWORD).andExpect(jsonPath("$.mustChangePassword").value(true)));
        call(get("/api/orders"), vic).andExpect(status().isForbidden()).andExpect(jsonPath("$.errors.mustChangePassword").exists());
        Cookie elsewhere = cookie(signIn("VIC", PASSWORD));
        changePassword(vic, "wrong", OTHER).andExpect(jsonPath("$.errors.currentPassword").exists());
        changePassword(vic, PASSWORD, PASSWORD).andExpect(jsonPath("$.errors.newPassword").exists());
        changePassword(vic, PASSWORD, "short").andExpect(jsonPath("$.errors.newPassword").exists());
        changePassword(vic, PASSWORD, OTHER).andExpect(status().isOk()).andExpect(jsonPath("$.mustChangePassword").value(false));
        call(get("/api/orders"), vic).andExpect(status().isOk());
        call(get("/api/orders"), elsewhere).andExpect(status().isUnauthorized());
        signIn("VIC", PASSWORD).andExpect(status().isUnauthorized());
        changePassword(null, OTHER, PASSWORD).andExpect(status().isUnauthorized());
    }

    @Test
    void aNewPasswordFromAnAdministratorSignsThemOut() throws Exception {
        Cookie boss = boss();
        String vic = user(boss, "VIC", PASSWORD, "Viewer");
        Cookie cookie = ownPassword("VIC", PASSWORD, OTHER);
        call(json(put("/api/users/" + vic + "/password"), Map.of("password", PASSWORD)), boss).andExpect(status().isOk());
        call(get("/api/orders"), cookie).andExpect(status().isUnauthorized());
        signIn("VIC", OTHER).andExpect(status().isUnauthorized());
        signIn("VIC", PASSWORD).andExpect(jsonPath("$.mustChangePassword").value(true));
    }

    // ---------- Permissions ----------

    @Test
    void aViewerSeesButChangesNothing() throws Exception {
        Cookie boss = boss();
        user(boss, "VIC", PASSWORD, "Viewer");
        Cookie vic = ownPassword("VIC", PASSWORD, OTHER);
        for (String path : List.of("/api/orders", "/api/items", "/api/asns", "/api/putaway/rules", "/api/shipments")) {
            call(get(path), vic).andExpect(status().isOk());
        }
        for (String path : List.of("/api/orders", "/api/items", "/api/asns", "/api/putaway/rules", "/api/carriers", "/api/users", "/api/roles")) {
            call(json(post(path), Map.of()), vic).andExpect(status().isForbidden());
        }
        call(json(put("/api/orders/" + UUID.randomUUID() + "/ship-to"), Map.of()), vic).andExpect(status().isForbidden());
        for (String path : List.of("/api/users", "/api/roles", "/api/roles/permissions")) {
            call(get(path), vic).andExpect(status().isForbidden());
        }
        call(get("/api/navigation/modules"), vic).andExpect(content().string(not(containsString("/admin/users"))));
        call(get("/api/navigation/modules"), boss).andExpect(content().string(containsString("/admin/users")));
    }

    @Test
    void roleChangesCountAtOnce() throws Exception {
        Cookie boss = boss();
        JsonNode role = role(boss, "Clerk", "DESKTOP", "ORDERS");
        String id = user(boss, "CLERK", PASSWORD, "Clerk");
        Cookie clerk = ownPassword("CLERK", PASSWORD, OTHER);
        call(json(post("/api/orders"), Map.of()), clerk).andExpect(status().isBadRequest());
        call(json(post("/api/items"), Map.of()), clerk).andExpect(status().isForbidden());
        Map<String, Object> input = new HashMap<>(Map.of("name", "Clerk", "permissions", List.of("DESKTOP", "MASTER_DATA"), "version", role.get("version").asLong()));
        call(json(put("/api/roles/" + role.get("id").asString()), input), boss).andExpect(status().isOk());
        call(json(post("/api/orders"), Map.of()), clerk).andExpect(status().isForbidden());
        call(json(post("/api/items"), Map.of()), clerk).andExpect(status().isBadRequest());
        call(json(put("/api/roles/" + role.get("id").asString()), input), boss).andExpect(status().isConflict());
        // Without any desktop role they are out.
        JsonNode user = body(call(get("/api/users/" + id), boss));
        call(json(put("/api/users/" + id), Map.of("userCode", "CLERK", "displayName", "C", "status", "ACTIVE", "roleIds", List.of(),
                "version", user.get("version").asLong())), boss).andExpect(status().isOk());
        call(get("/api/orders"), clerk).andExpect(status().isUnauthorized());
        signIn("CLERK", OTHER).andExpect(status().isForbidden());
    }

    @Test
    void devicePermissionsAreChecked() throws Exception {
        Cookie boss = boss();
        role(boss, "Picker", "DEVICE_PICK");
        call(json(post("/api/users"), Map.of("userCode", "ANN", "displayName", "Ann", "status", "ACTIVE", "pin", "8264",
                "roleIds", List.of(roleId(boss, "Picker")))), boss).andExpect(status().isCreated());
        ResultActions signedIn = call(json(post("/api/rf/session"), Map.of("userCode", "ANN", "pin", "8264")), null)
                .andExpect(jsonPath("$.permissions", hasSize(1)));
        Cookie ann = cookieNamed(signedIn, "wms_device");
        call(get("/api/rf/picking"), ann).andExpect(status().isOk());
        for (String path : List.of("/api/rf/receiving/asns", "/api/rf/putaway", "/api/rf/packing/stations", "/api/rf/loading/doors")) {
            call(get(path), ann).andExpect(status().isForbidden());
        }
    }

    @Test
    void rolesAreKeptAndChecked() throws Exception {
        Cookie boss = boss();
        call(get("/api/roles"), boss).andExpect(jsonPath("$", hasSize(4))).andExpect(jsonPath("$[0].name").value("Administrator"))
                .andExpect(jsonPath("$[0].permissions", hasSize(Permission.values().length)));
        call(get("/api/roles/permissions"), boss).andExpect(jsonPath("$", hasSize(Permission.values().length)));
        String admin = "/api/roles/" + Role.ADMINISTRATOR;
        Map<String, Object> input = Map.of("name", "Administrator", "permissions", List.of(), "version", 0);
        call(json(put(admin), input), boss).andExpect(status().isConflict());
        call(delete(admin), boss).andExpect(status().isConflict());
        call(json(post("/api/roles"), Map.of("name", "operator", "permissions", List.of())), boss).andExpect(jsonPath("$.errors.name").exists());
        call(json(post("/api/roles"), Map.of("name", "Odd", "permissions", List.of("ORDERS"))), boss).andExpect(jsonPath("$.errors.permissions").exists());
        JsonNode role = role(boss, "Clerk", "DESKTOP");
        user(boss, "CLERK", PASSWORD, "Clerk");
        call(delete("/api/roles/" + role.get("id").asString()), boss).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("1 user has this role")));
        JsonNode unused = role(boss, "Unused", "DEVICE_PICK");
        call(delete("/api/roles/" + unused.get("id").asString()), boss).andExpect(status().isNoContent());
    }

    @Test
    void someoneCanAlwaysManageUsers() throws Exception {
        Cookie boss = boss();
        AppUser me = users.findByUserCode("BOSS").orElseThrow();
        String url = "/api/users/" + me.getId();
        Map<String, Object> input = new HashMap<>(Map.of("userCode", "BOSS", "displayName", "B", "status", "INACTIVE", "version", version(boss, url)));
        call(json(put(url), input), boss).andExpect(status().isConflict()).andExpect(jsonPath("$.detail", containsString("nobody could manage users")));
    }

    @Test
    void theLastAdministratorKeepsTheRole() throws Exception {
        Cookie boss = boss();
        String url = "/api/users/" + users.findByUserCode("BOSS").orElseThrow().getId();
        call(json(put(url), Map.of("userCode", "BOSS", "displayName", "B", "status", "ACTIVE", "roleIds", List.of(), "version", version(boss, url))), boss)
                .andExpect(status().isConflict());
    }

    // ---------- Helpers ----------

    /** Makes the first administrator and signs them in. */
    private Cookie boss() throws Exception {
        return cookie(call(json(post("/api/session/setup"), Map.of("userCode", "BOSS", "displayName", "The Boss", "password", PASSWORD)), null)
                .andExpect(status().isOk()));
    }

    private String user(Cookie boss, String code, String password, String role) throws Exception {
        return mapper.readTree(call(json(post("/api/users"), Map.of("userCode", code, "displayName", code, "status", "ACTIVE", "password", password,
                "roleIds", List.of(roleId(boss, role)))), boss).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .get("id").asString();
    }

    private JsonNode role(Cookie boss, String name, String... permissions) throws Exception {
        return mapper.readTree(call(json(post("/api/roles"), Map.of("name", name, "permissions", List.of(permissions))), boss)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private String roleId(Cookie boss, String name) throws Exception {
        for (JsonNode role : body(call(get("/api/roles"), boss))) {
            if (role.get("name").asString().equals(name)) {
                return role.get("id").asString();
            }
        }
        throw new AssertionError("no role " + name);
    }

    private long version(Cookie boss, String url) throws Exception {
        return body(call(get(url), boss)).get("version").asLong();
    }

    /** Signs in and chooses a password of their own. */
    private Cookie ownPassword(String code, String given, String own) throws Exception {
        Cookie cookie = cookie(signIn(code, given).andExpect(status().isOk()));
        changePassword(cookie, given, own).andExpect(status().isOk());
        return cookie;
    }

    private ResultActions changePassword(Cookie who, String current, String next) throws Exception {
        return call(json(post("/api/session/password"), Map.of("currentPassword", current, "newPassword", next)), who);
    }

    private ResultActions signIn(String code, String password) throws Exception {
        return call(json(post("/api/session"), Map.of("userCode", code, "password", password)), null);
    }

    private ResultActions signInRequest(String code, String password, String site) throws Exception {
        return call(json(post("/api/session"), Map.of("userCode", code, "password", password)).header("Sec-Fetch-Site", site), null);
    }

    private static Cookie cookie(ResultActions result) throws Exception {
        return cookieNamed(result, "wms_desktop");
    }

    private static Cookie cookieNamed(ResultActions result, String name) throws Exception {
        MockHttpServletResponse response = result.andExpect(status().isOk()).andReturn().getResponse();
        return response.getCookie(name);
    }

    private ResultActions call(MockHttpServletRequestBuilder request, Cookie who) throws Exception {
        em.flush();
        em.clear();
        return mvc.perform(who == null ? request : request.cookie(who));
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, Object body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
    }

    private JsonNode body(ResultActions result) throws Exception {
        return mapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
}
