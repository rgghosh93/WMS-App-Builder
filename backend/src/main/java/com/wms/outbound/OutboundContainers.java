package com.wms.outbound;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.wms.common.ActiveStatus;
import com.wms.common.api.ApiException;
import com.wms.inventory.InventoryRepository;
import com.wms.masterdata.container.Container;
import com.wms.masterdata.container.ContainerCategory;
import com.wms.masterdata.container.ContainerRepository;
import com.wms.masterdata.container.ContainerStatus;
import com.wms.masterdata.container.ContainerType;
import com.wms.masterdata.container.ContainerTypeRepository;
import com.wms.masterdata.container.NewLpnCheck;

/** The containers pickers pick into: reusable totes and the like, and single-use cartons (oLPNs). */
@Component
class OutboundContainers {

    private final ContainerRepository containers;
    private final ContainerTypeRepository types;
    private final InventoryRepository inventory;
    private final NewLpnCheck newLpns;

    OutboundContainers(ContainerRepository containers, ContainerTypeRepository types, InventoryRepository inventory,
                       NewLpnCheck newLpns) {
        this.containers = containers;
        this.types = types;
        this.inventory = inventory;
        this.newLpns = newLpns;
    }

    boolean exists(String lpnId) {
        return containers.findByLpnId(lpnId).isPresent();
    }

    /** Throws if the LPN can't be picked into. An LPN not seen before is fine: it is a new carton. */
    void check(String lpnId) {
        Container container = containers.findByLpnId(lpnId).orElse(null);
        if (container == null) {
            newLpns.problemWith(lpnId).ifPresent(problem -> {
                throw refuse(problem);
            });
            return;
        }
        if (container.getContainerType().getCategory() == ContainerCategory.BIN) {
            throw refuse(lpnId + " is a storage bin, not an outbound container");
        }
        if (container.getStatus() == ContainerStatus.IN_USE) {
            throw refuse(lpnId + " is already in use");
        }
        if (container.getStatus() != ContainerStatus.AVAILABLE) {
            throw refuse(lpnId + " can't be used: it is "
                    + (container.getStatus() == ContainerStatus.ON_HOLD ? "on hold" : "retired"));
        }
        if (inventory.countByContainer_Id(container.getId()) > 0 || containers.countByParent_Id(container.getId()) > 0) {
            throw refuse(lpnId + " is not empty");
        }
        if (container.getParent() != null) {
            throw refuse(lpnId + " is inside " + container.getParent().getLpnId());
        }
    }

    /** Throws unless the LPN is free to be given to a new carton. */
    void checkNewCarton(String lpnId) {
        if (exists(lpnId)) {
            throw refuse(lpnId + " is already used by another container");
        }
        check(lpnId);
    }

    /** An outbound container was emptied by packing: a tote is ready for reuse, a single-use carton is done. */
    void finish(Container container) {
        if (inventory.countByContainer_Id(container.getId()) > 0 || containers.countByParent_Id(container.getId()) > 0) {
            return;
        }
        if (container.isPermanent()) {
            container.setStatus(ContainerStatus.AVAILABLE);
        } else {
            container.setStatus(ContainerStatus.RETIRED);
            container.setCurrentLocation(null);
        }
    }

    /** Carton types a new LPN can be given. */
    List<ContainerType> cartonTypes() {
        return types.findAll().stream()
                .filter(t -> t.getStatus() == ActiveStatus.ACTIVE && !t.isReusable()
                        && t.getCategory() == ContainerCategory.CARTON)
                .sorted((a, b) -> a.getContainerTypeId().compareTo(b.getContainerTypeId()))
                .toList();
    }

    /** Takes a container into use, creating the carton if the LPN is new. It travels with the picker, so it has no location. */
    Container open(String lpnId, UUID cartonTypeId) {
        Container container = containers.findByLpnId(lpnId).orElse(null);
        if (container == null) {
            ContainerType type = cartonTypes().stream().filter(t -> t.getId().equals(cartonTypeId)).findFirst()
                    .orElseThrow(() -> ApiException.invalid("Choose a carton type",
                            Map.of("containerTypeId", "choose a carton type")));
            container = new Container(UUID.randomUUID());
            container.setLpnId(lpnId);
            container.setContainerType(type);
            container.setPermanent(false);
            container.setStatus(ContainerStatus.IN_USE);
            return containers.saveAndFlush(container);
        }
        container.setStatus(ContainerStatus.IN_USE);
        container.setCurrentLocation(null);
        return containers.saveAndFlush(container);
    }

    /** Lets go of a task's empty container: a tote is ready for reuse, a carton LPN never came to be. */
    void release(PickTask task) {
        Container container = task.getToContainer();
        if (container == null) {
            return;
        }
        task.clearToContainer();
        if (container.isPermanent()) {
            container.setStatus(ContainerStatus.AVAILABLE);
        } else {
            containers.flush();
            containers.delete(container);
        }
    }

    /** Takes single-use LPNs out of service once everything has been picked out of them, outwards from this one. */
    void retireIfEmptied(Container container) {
        Container current = container;
        while (current != null && !current.isPermanent()) {
            containers.flush();
            if (inventory.countByContainer_Id(current.getId()) > 0 || containers.countByParent_Id(current.getId()) > 0) {
                return;
            }
            Container parent = current.getParent();
            current.setStatus(ContainerStatus.RETIRED);
            current.setParent(null);
            current.setCurrentLocation(null);
            current = parent;
        }
    }

    private static ApiException refuse(String message) {
        return ApiException.invalid(message, Map.of("lpnId", message));
    }
}
