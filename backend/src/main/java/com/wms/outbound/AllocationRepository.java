package com.wms.outbound;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

public interface AllocationRepository extends JpaRepository<Allocation, UUID>, JpaSpecificationExecutor<Allocation> {

    /** Lists allocations with their order, item and stock loaded in the same query. */
    @Override
    @EntityGraph(attributePaths = {"line", "line.order", "line.item", "inventory", "inventory.container", "inventory.location"})
    Page<Allocation> findAll(Specification<Allocation> spec, Pageable pageable);

    @EntityGraph(attributePaths = {"line", "line.order", "line.item", "inventory", "inventory.container", "inventory.location"})
    List<Allocation> findByLine_Order_IdOrderByCreatedAtAsc(UUID orderId);

    List<Allocation> findByLine_Order_Id(UUID orderId);

    /** Quantity already reserved from each of these inventory records: rows of (inventory id, quantity). */
    @Query("select a.inventory.id, sum(a.quantity) from Allocation a where a.inventory.id in :inventoryIds group by a.inventory.id")
    List<Object[]> sumByInventory(Collection<UUID> inventoryIds);

    @Query("select coalesce(sum(a.quantity), 0) from Allocation a")
    long totalQuantity();
}
