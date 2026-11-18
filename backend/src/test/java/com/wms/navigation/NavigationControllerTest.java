package com.wms.navigation;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class NavigationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void returnsModulesWithPages() throws Exception {
        mockMvc.perform(get("/api/navigation/modules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", greaterThan(0)))
                .andExpect(jsonPath("$[*].id", hasItem("inventory")))
                .andExpect(jsonPath("$[*].pages[*].id", hasItem("warehouse-map")))
                .andExpect(jsonPath("$[*].pages[*].route", everyItem(notNullValue())))
                .andExpect(jsonPath("$[*].pages[*].status", everyItem(org.hamcrest.Matchers.oneOf("AVAILABLE", "PLANNED"))));
    }
}
