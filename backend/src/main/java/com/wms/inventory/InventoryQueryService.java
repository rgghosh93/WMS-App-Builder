package com.wms.inventory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wms.common.api.ApiException;
import com.wms.inventory.InventoryViews.ContainerDetail;
import com.wms.inventory.InventoryViews.ContainerNode;
import com.wms.inventory.InventoryViews.ContainerSummary;
import com.wms.inventory.InventoryViews.InventoryLine;
import com.wms.inventory.InventoryViews.LocationDetail;
import com.wms.inventory.InventoryViews.LocationRef;
import com.wms.inventory.InventoryViews.LocationSummary;
import com.wms.inventory.InventoryViews.LpnRef;
import com.wms.masterdata.container.Container;
import com.wms.masterdata.container.ContainerRepository;
import com.wms.masterdata.container.ContainerResponse.TypeSummary;
import com.wms.masterdata.location.Location;
import com.wms.masterdata.location.LocationRepository;

/** Builds the inventory views by location and by container, following containers inside containers. */
@Service
@Transactional(readOnly = true)
public class InventoryQueryService {

    /** Guards against a mistaken loop of containers inside each other. */
    static final int MAX_NESTING = 20;

    private static final Comparator<InventoryLine> LINE_ORDER = Comparator
            .comparing((InventoryLine l) -> l.item().itemId())
            .thenComparing(InventoryLine::lotNumber, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(InventoryLine::serialNumber, Comparator.nullsFirst(Comparator.naturalOrder()));

    private static final Comparator<Node> BY_LPN = Comparator.comparing(n -> n.container.getLpnId());

    private final LocationRepository locationRepository;
    private final ContainerRepository containerRepository;
    private final InventoryRepository inventoryRepository;
    private final List<AllocationLookup> allocationLookups;

    public InventoryQueryService(LocationRepository locationRepository, ContainerRepository containerRepository,
                                 InventoryRepository inventoryRepository, List<AllocationLookup> allocationLookups) {
        this.locationRepository = locationRepository;
        this.containerRepository = containerRepository;
        this.inventoryRepository = inventoryRepository;
        this.allocationLookups = allocationLookups;
    }

    // ---------- By location ----------

    public Page<LocationSummary> locations(InventoryLocationSearch search, Pageable pageable) {
        Page<Location> page = locationRepository.findAll(search.toSpecification(), pageable);
        Map<UUID, Node> roots = loadLocations(page.getContent().stream().map(Location::getId).toList());
        return page.map(location -> {
            Node root = roots.get(location.getId());
            return new LocationSummary(LocationRef.of(location), root.totalQuantity(), root.items().size(),
                    root.containerCount(), root.lines.size());
        });
    }

    public LocationDetail location(UUID locationId) {
        Location location = locationRepository.findById(locationId)
                .orElseThrow(() -> ApiException.notFound("Location not found"));
        Node root = loadLocations(List.of(locationId)).get(locationId);
        Map<UUID, Integer> allocated = allocated(root);
        return new LocationDetail(LocationRef.of(location), root.totalQuantity(), root.items().size(),
                root.containerCount(),
                lines(root, allocated),
                root.children.stream().map(n -> toView(n, allocated)).toList());
    }

    // ---------- By container ----------

    public Page<ContainerSummary> containers(ContainerInventorySearch search, Pageable pageable) {
        Page<Container> page = containerRepository.findAll(search.toSpecification(), pageable);
        Map<UUID, Node> nodes = loadSubtrees(page.getContent());
        return page.map(c -> {
            Node node = nodes.get(c.getId());
            return new ContainerSummary(c.getId(), c.getLpnId(), c.isPermanent(), TypeSummary.of(c.getContainerType()),
                    c.getStatus(), locationOf(c), pathTo(c), node.totalQuantity(), node.items().size(),
                    node.containerCount(), node.lines.size());
        });
    }

    public ContainerDetail container(UUID containerId) {
        Container container = containerRepository.findWithDetailsById(containerId)
                .orElseThrow(() -> ApiException.notFound("Container not found"));
        Node node = loadSubtrees(List.of(container)).get(containerId);
        return new ContainerDetail(locationOf(container), pathTo(container), toView(node, allocated(node)));
    }

    /** Where a container is: its own location, or the location of the outermost container it sits in. */
    private static LocationRef locationOf(Container c) {
        Container outer = c;
        for (int depth = 0; outer.getParent() != null && depth < MAX_NESTING; depth++) {
            outer = outer.getParent();
        }
        return outer.getCurrentLocation() == null ? null : LocationRef.of(outer.getCurrentLocation());
    }

    /** The containers this one sits inside, outermost first. Empty for a container not inside another. */
    private static List<LpnRef> pathTo(Container c) {
        List<LpnRef> path = new ArrayList<>();
        Container parent = c.getParent();
        for (int depth = 0; parent != null && depth < MAX_NESTING; depth++) {
            path.add(new LpnRef(parent.getId(), parent.getLpnId()));
            parent = parent.getParent();
        }
        Collections.reverse(path);
        return path;
    }

    // ---------- Loading ----------

    /** One tree per location: its loose stock, and the containers in it with everything inside them. */
    private Map<UUID, Node> loadLocations(List<UUID> locationIds) {
        Map<UUID, Node> roots = new LinkedHashMap<>();
        locationIds.forEach(id -> roots.put(id, new Node(null)));
        if (locationIds.isEmpty()) {
            return roots;
        }
        List<Container> top = containerRepository.findByCurrentLocation_IdIn(locationIds);
        Map<UUID, Node> nodes = loadSubtrees(top);
        for (Container c : top) {
            roots.get(c.getCurrentLocation().getId()).children.add(nodes.get(c.getId()));
        }
        inventoryRepository.findByLocation_IdIn(locationIds)
                .forEach(r -> roots.get(r.getLocation().getId()).lines.add(r));
        roots.values().forEach(n -> n.children.sort(BY_LPN));
        return roots;
    }

    /**
     * Loads each starting container with everything inside it, at any depth, and its stock.
     * Returns every node loaded, keyed by container id. Starting containers may contain one another.
     */
    private Map<UUID, Node> loadSubtrees(Collection<Container> starts) {
        Map<UUID, Node> nodes = new HashMap<>();
        List<Container> frontier = new ArrayList<>();
        for (Container c : starts) {
            if (nodes.putIfAbsent(c.getId(), new Node(c)) == null) {
                frontier.add(c);
            }
        }
        Set<UUID> linked = new HashSet<>();
        for (int depth = 0; !frontier.isEmpty() && depth < MAX_NESTING; depth++) {
            List<Container> children = containerRepository.findByParent_IdIn(frontier.stream().map(Container::getId).toList());
            frontier = new ArrayList<>();
            for (Container child : children) {
                Node node = nodes.get(child.getId());
                if (node == null) {
                    node = new Node(child);
                    nodes.put(child.getId(), node);
                    frontier.add(child);
                }
                if (linked.add(child.getId())) {
                    nodes.get(child.getParent().getId()).children.add(node);
                }
            }
        }
        if (!nodes.isEmpty()) {
            inventoryRepository.findByContainer_IdIn(nodes.keySet())
                    .forEach(r -> nodes.get(r.getContainer().getId()).lines.add(r));
        }
        nodes.values().forEach(n -> n.children.sort(BY_LPN));
        return nodes;
    }

    /** Reserved quantities for every inventory record in a tree. */
    private Map<UUID, Integer> allocated(Node root) {
        List<UUID> ids = new ArrayList<>();
        collectRecordIds(root, ids);
        Map<UUID, Integer> result = new HashMap<>();
        allocationLookups.forEach(lookup -> lookup.allocatedByInventory(ids).forEach((id, n) -> result.merge(id, n, Integer::sum)));
        return result;
    }

    private static void collectRecordIds(Node node, List<UUID> ids) {
        node.lines.forEach(r -> ids.add(r.getId()));
        node.children.forEach(child -> collectRecordIds(child, ids));
    }

    private static List<InventoryLine> lines(Node node, Map<UUID, Integer> allocated) {
        return node.lines.stream()
                .map(r -> InventoryLine.of(r, allocated.getOrDefault(r.getId(), 0)))
                .sorted(LINE_ORDER)
                .toList();
    }

    private static ContainerNode toView(Node node, Map<UUID, Integer> allocated) {
        Container c = node.container;
        return new ContainerNode(c.getId(), c.getLpnId(), c.isPermanent(), TypeSummary.of(c.getContainerType()),
                c.getStatus(), node.totalQuantity(), node.items().size(), lines(node, allocated),
                node.children.stream().map(n -> toView(n, allocated)).toList());
    }

    /** A location (container == null) or a container, with its own stock and what is inside it. */
    private static final class Node {
        final Container container;
        final List<InventoryRecord> lines = new ArrayList<>();
        final List<Node> children = new ArrayList<>();

        Node(Container container) {
            this.container = container;
        }

        long totalQuantity() {
            return lines.stream().mapToLong(InventoryRecord::getQuantity).sum()
                    + children.stream().mapToLong(Node::totalQuantity).sum();
        }

        Set<UUID> items() {
            Set<UUID> items = new HashSet<>();
            lines.forEach(l -> items.add(l.getItem().getId()));
            children.forEach(c -> items.addAll(c.items()));
            return items;
        }

        int containerCount() {
            return children.size() + children.stream().mapToInt(Node::containerCount).sum();
        }
    }
}
