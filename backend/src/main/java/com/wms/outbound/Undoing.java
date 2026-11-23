package com.wms.outbound;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import com.wms.common.api.ApiException;
import com.wms.facility.Site;
import com.wms.inventory.InventoryRecord;
import com.wms.inventory.StockFreed;
import com.wms.inventory.StockLedger;
import com.wms.masterdata.container.Container;
import com.wms.masterdata.container.ContainerRepository;
import com.wms.masterdata.location.Location;
import com.wms.masterdata.location.LocationType;

/**
 * Takes an order out of everything that was going on for it: its picks, its packing, its cartons and its shipments. Stock
 * not picked is free straight away. Stock already picked stays in the container it is in, no longer reserved, until
 * someone moves it back to storage; until then no order can have it.
 */
@Component
class Undoing {

    private final PickTaskRepository picks;
    private final PackTaskRepository packs;
    private final AllocationRepository allocations;
    private final OutboundContainers outbound;
    private final ContainerRepository containers;
    private final ShippingService shipping;
    private final ApplicationEventPublisher publisher;

    Undoing(PickTaskRepository picks, PackTaskRepository packs, AllocationRepository allocations, OutboundContainers outbound,
            ContainerRepository containers, ShippingService shipping, ApplicationEventPublisher publisher) {
        this.picks = picks;
        this.packs = packs;
        this.allocations = allocations;
        this.outbound = outbound;
        this.containers = containers;
        this.shipping = shipping;
        this.publisher = publisher;
    }

    /**
     * @param openPicks picks nobody had done yet
     * @param tasks     the pick tasks they were on
     * @param released  eaches that were reserved, in all
     * @param left      what has to be put back, in words: how much, and where it is; null if nothing
     */
    record Undone(int openPicks, List<String> tasks, long released, String left) {
    }

    /** @param order locked, with nothing of it shipped */
    Undone undo(OutboundOrder order) {
        UUID id = order.getId();
        List<PackTask> packTasks = packs.findByOrderId(id);
        List<PackCarton> cartons = packTasks.stream().flatMap(t -> t.getCartons().stream())
                .filter(c -> c.getOrder().getId().equals(id) && c.getStatus() != CartonStatus.CANCELLED).toList();
        cartons.stream().filter(PackCarton::isLoaded).findFirst().ifPresent(c -> {
            throw ApiException.conflict("%s of %s is loaded on %s. Take it off first.".formatted(c.getContainer().getLpnId(),
                    order.getOrderNumber(), c.getShipment().getManifest().getManifestNumber()), Map.of());
        });

        int openPicks = 0;
        List<String> touched = new ArrayList<>();
        for (PickTask task : picks.findByOrderId(id)) {
            boolean wasOpen = task.getStatus() != PickTaskStatus.CANCELLED;
            int n = task.undoFor(order);
            if (n > 0) {
                touched.add(task.getTaskNumber());
                openPicks += n;
            }
            if (wasOpen && task.getStatus() == PickTaskStatus.CANCELLED) {
                outbound.release(task);
            }
        }
        List<PackTask> ended = packTasks.stream().filter(t -> callOff(t, order)).toList();
        picks.flush();

        List<Allocation> reserved = allocations.findByLine_Order_Id(id);
        long released = reserved.stream().mapToLong(Allocation::getQuantity).sum();
        Map<String, Integer> left = new TreeMap<>();
        for (Allocation a : reserved) {
            InventoryRecord r = a.getInventory();
            Location at = StockLedger.locationOf(r);
            if (at == null || at.getType() != LocationType.STORAGE) {
                left.merge((r.getContainer() == null ? "" : "in " + r.getContainer().getLpnId())
                        + (at == null ? "" : " at " + at.getLocationId()), a.getQuantity(), Integer::sum);
            }
            publisher.publishEvent(new StockFreed(Site.current(), r.getItem().getId()));
        }
        allocations.deleteAll(reserved);
        allocations.flush();
        ended.forEach(t -> outbound.finish(t.getContainer()));
        return new Undone(openPicks, touched, released, left.isEmpty() ? null : left.entrySet().stream()
                .map(e -> "%,d EA %s".formatted(e.getValue(), e.getKey().trim())).collect(Collectors.joining(", ")));
    }

    /** Whether the task ended with this. */
    private boolean callOff(PackTask task, OutboundOrder order) {
        boolean open = task.getStatus().isOpen();
        for (PackCarton carton : List.copyOf(task.getCartons())) {
            if (!carton.getOrder().getId().equals(order.getId()) || carton.getStatus() == CartonStatus.CANCELLED) {
                continue;
            }
            if (carton.getLines().isEmpty()) {
                // Opened but nothing in it yet: it never came to be.
                Container container = carton.getContainer();
                task.getCartons().remove(carton);
                packs.flush();
                containers.delete(container);
            } else {
                shipping.takeBack(carton);
                carton.cancel();
            }
        }
        task.getDetails().stream().filter(d -> d.getOrder().getId().equals(order.getId())).forEach(PackTaskDetail::cancel);
        task.touch();
        if (!open || !task.isDone()) {
            return false;
        }
        if (task.isCalledOff()) {
            task.cancel();
        } else {
            task.complete();
        }
        return true;
    }
}
