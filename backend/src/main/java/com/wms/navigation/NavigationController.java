package com.wms.navigation;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.wms.users.DesktopUser;

@RestController
@RequestMapping("/api/navigation")
public class NavigationController {

    private final PageCatalog catalog;

    public NavigationController(PageCatalog catalog) {
        this.catalog = catalog;
    }

    /** The modules and pages this person can see, for the home screen and the sidebar directory. */
    @GetMapping("/modules")
    public List<ModuleDefinition> modules(@RequestAttribute(DesktopUser.ATTRIBUTE) DesktopUser user) {
        return catalog.modules().stream()
                .map(m -> new ModuleDefinition(m.id(), m.name(), m.description(), m.icon(), m.pages().stream()
                        .filter(p -> p.requires() == null || user.permissions().contains(p.requires())).toList()))
                .filter(m -> !m.pages().isEmpty())
                .toList();
    }
}
