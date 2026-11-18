package com.wms.navigation;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/navigation")
public class NavigationController {

    private final PageCatalog catalog;

    public NavigationController(PageCatalog catalog) {
        this.catalog = catalog;
    }

    /** All modules and their pages, for the home screen and the sidebar directory. */
    @GetMapping("/modules")
    public List<ModuleDefinition> modules() {
        return catalog.modules();
    }
}
