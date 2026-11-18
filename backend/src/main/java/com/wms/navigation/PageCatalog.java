package com.wms.navigation;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * The catalog of WMS modules and pages. It is loaded from metadata
 * ({@code catalog/pages.json}) rather than hard-coded, so new pages can be
 * registered without changing code.
 */
@Component
public class PageCatalog {

    static final String CATALOG_LOCATION = "catalog/pages.json";

    private final List<ModuleDefinition> modules;

    public PageCatalog(ObjectMapper objectMapper) {
        this.modules = load(objectMapper, new ClassPathResource(CATALOG_LOCATION));
    }

    public List<ModuleDefinition> modules() {
        return modules;
    }

    static List<ModuleDefinition> load(ObjectMapper objectMapper, Resource resource) {
        try (InputStream in = resource.getInputStream()) {
            List<ModuleDefinition> loaded = objectMapper.readValue(in, new TypeReference<List<ModuleDefinition>>() { });
            validate(loaded);
            return List.copyOf(loaded);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read page catalog " + resource.getDescription(), e);
        }
    }

    private static void validate(List<ModuleDefinition> modules) {
        Set<String> moduleIds = new HashSet<>();
        Set<String> pageIds = new HashSet<>();
        Set<String> routes = new HashSet<>();
        for (ModuleDefinition module : modules) {
            requireText(module.id(), "module id");
            requireText(module.name(), "module name for " + module.id());
            if (!moduleIds.add(module.id())) {
                throw new IllegalStateException("Duplicate module id in page catalog: " + module.id());
            }
            for (PageDefinition page : module.pages()) {
                requireText(page.id(), "page id in module " + module.id());
                requireText(page.title(), "page title for " + page.id());
                requireText(page.route(), "page route for " + page.id());
                if (page.status() == null) {
                    throw new IllegalStateException("Missing status for page " + page.id());
                }
                if (!pageIds.add(page.id())) {
                    throw new IllegalStateException("Duplicate page id in page catalog: " + page.id());
                }
                if (!routes.add(page.route())) {
                    throw new IllegalStateException("Duplicate page route in page catalog: " + page.route());
                }
            }
        }
    }

    private static void requireText(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing " + what + " in page catalog");
        }
    }
}
