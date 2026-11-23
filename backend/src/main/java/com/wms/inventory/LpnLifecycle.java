package com.wms.inventory;

import java.util.List;

import org.springframework.stereotype.Component;

import com.wms.masterdata.container.Container;
import com.wms.masterdata.container.ContainerCategory;
import com.wms.masterdata.container.ContainerRepository;
import com.wms.masterdata.container.ContainerStatus;

/** What becomes of LPNs when stock is corrected out of them. */
@Component
public class LpnLifecycle {

    private final ContainerRepository containers;
    private final InventoryRepository inventory;
    private final List<EmptiedLpns> listeners;

    LpnLifecycle(ContainerRepository containers, InventoryRepository inventory, List<EmptiedLpns> listeners) {
        this.containers = containers;
        this.inventory = inventory;
        this.listeners = listeners;
    }

    /** Takes a single-use LPN out of service if nothing is left in it, outwards from this one. A tote is ready for reuse. */
    public void retireIfEmptied(Container container) {
        Container current = container;
        while (current != null) {
            inventory.flush();
            if (inventory.countByContainer_Id(current.getId()) > 0 || containers.countByParent_Id(current.getId()) > 0) {
                return;
            }
            Container emptied = current;
            listeners.forEach(l -> l.emptied(emptied));
            if (current.isPermanent()) {
                if (current.getStatus() == ContainerStatus.IN_USE && current.getContainerType().getCategory() != ContainerCategory.BIN) {
                    current.setStatus(ContainerStatus.AVAILABLE);
                }
                return;
            }
            Container parent = current.getParent();
            current.setStatus(ContainerStatus.RETIRED);
            current.setParent(null);
            current.setCurrentLocation(null);
            current = parent;
        }
    }
}
