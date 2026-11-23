package com.wms.users;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import tools.jackson.databind.ObjectMapper;

/** As it runs in the cloud: nobody is on the computer it runs on, so the first administrator takes the setup key. */
@SpringBootTest(properties = {"wms.setup.on-this-computer=false", "wms.setup.key=  a-long-enough-setup-key-123  "})
@Transactional
class CloudSetupTest {

    private static final String PASSWORD = "correct horse battery";

    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper mapper;
    private MockMvc mvc;

    @BeforeEach
    void nobodySignedIn() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void theFirstAdministratorTakesTheSetupKey() throws Exception {
        mvc.perform(get("/api/session")).andExpect(jsonPath("$.state").value("SETUP"))
                .andExpect(jsonPath("$.setupHere").value(false)).andExpect(jsonPath("$.setupWithKey").value(true));
        // Not even on the computer itself.
        setUp(null).andExpect(status().isForbidden());
        setUp("a-long-enough-setup-key-12").andExpect(status().isForbidden()).andExpect(jsonPath("$.errors.setupKey").value("isn't right"));
        setUp("A-LONG-ENOUGH-SETUP-KEY-123").andExpect(status().isForbidden());
        setUp(" a-long-enough-setup-key-123 ").andExpect(status().isOk()).andExpect(jsonPath("$.userCode").value("BOSS"));
        // Once there is one, the key opens nothing.
        setUp("a-long-enough-setup-key-123").andExpect(status().isConflict());
        mvc.perform(get("/api/session")).andExpect(jsonPath("$.state").value("SIGNED_OUT")).andExpect(jsonPath("$.setupWithKey").value(false));
    }

    @Test
    void theHealthCheckNeedsNoSignIn() throws Exception {
        mvc.perform(get("/api/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/api/orders")).andExpect(status().isUnauthorized());
    }

    @Test
    void unknownApiPathsAreNotTheApp() throws Exception {
        // Whether or not the app is packaged in, the API answers for itself rather than with the app.
        mvc.perform(get("/api/nothing")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/nothing/deeper.js")).andExpect(status().isUnauthorized());
    }

    @Test
    void aShortKeyOpensNothing() throws Exception {
        FirstSetup weak = new FirstSetup("too-short", false);
        assertThat(weak.withKey()).isFalse();
        assertThat(weak.opens("too-short")).isFalse();
    }

    private ResultActions setUp(String key) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("userCode", "boss", "displayName", "The Boss", "password", PASSWORD));
        body.put("setupKey", key);
        return mvc.perform(post("/api/session/setup").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)));
    }
}
