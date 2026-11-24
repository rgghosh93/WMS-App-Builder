package com.wms.navigation;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.wms.users.DesktopUser;
import com.wms.users.Permission;

@RestController
@RequestMapping("/api/navigation")
public class NavigationController {

    private final PageCatalog catalog;
    private final boolean builder;

    /** @param builder the App Builder is switched on here; its page is hidden where it isn't */
    public NavigationController(PageCatalog catalog, @Value("${wms.builder.enabled:false}") boolean builder) {
        this.catalog = catalog;
        this.builder = builder;
    }

    /** The modules and pages this person can see, for the home screen and the sidebar directory. */
    @GetMapping("/modules")
    public List<ModuleDefinition> modules(@RequestAttribute(DesktopUser.ATTRIBUTE) DesktopUser user) {
        return catalog.modules().stream()
                .map(m -> new ModuleDefinition(m.id(), m.name(), m.description(), m.icon(), m.pages().stream()
                        .filter(p -> p.requires() == null || user.permissions().contains(p.requires()))
                        .filter(p -> builder || p.requires() != Permission.APP_BUILDER).toList()))
                .filter(m -> !m.pages().isEmpty())
                .toList();
    }
}
