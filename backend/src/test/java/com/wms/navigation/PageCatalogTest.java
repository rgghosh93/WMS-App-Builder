package com.wms.navigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class PageCatalogTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();

    @Test
    void loadsShippedCatalog() {
        List<ModuleDefinition> modules = new PageCatalog(mapper).modules();

        assertThat(modules).isNotEmpty();
        assertThat(modules).allSatisfy(m -> assertThat(m.pages()).isNotEmpty());
    }

    @Test
    void rejectsDuplicatePageIds() {
        String json = """
                [{"id":"a","name":"A","pages":[
                  {"id":"p","title":"P1","route":"/a/1","status":"PLANNED"},
                  {"id":"p","title":"P2","route":"/a/2","status":"PLANNED"}]}]
                """;

        assertThatThrownBy(() -> PageCatalog.load(mapper, resourceOf(json)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate page id");
    }

    @Test
    void rejectsDuplicateRoutes() {
        String json = """
                [{"id":"a","name":"A","pages":[
                  {"id":"p1","title":"P1","route":"/same","status":"PLANNED"},
                  {"id":"p2","title":"P2","route":"/same","status":"PLANNED"}]}]
                """;

        assertThatThrownBy(() -> PageCatalog.load(mapper, resourceOf(json)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate page route");
    }

    @Test
    void rejectsMissingStatus() {
        String json = """
                [{"id":"a","name":"A","pages":[{"id":"p1","title":"P1","route":"/a"}]}]
                """;

        assertThatThrownBy(() -> PageCatalog.load(mapper, resourceOf(json)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Missing status");
    }

    private static Resource resourceOf(String json) {
        return new ByteArrayResource(json.getBytes(StandardCharsets.UTF_8));
    }
}
