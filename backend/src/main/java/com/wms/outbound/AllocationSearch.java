package com.wms.outbound;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.data.jpa.domain.Specification;

import com.wms.inventory.InventoryStatus;

import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

/**
 * Filters for listing allocations.
 *
 * @param q               free text matched (contains, any case) against order number, customer,
 *                        item, lot, serial, pick location and LPN
 * @param orderStatus     exact status of the order the allocation belongs to
 * @param inventoryStatus exact current status of the reserved stock (e.g. to find stock put on hold after allocation)
 */
public record AllocationSearch(String q, OrderStatus orderStatus, InventoryStatus inventoryStatus) {

    Specification<Allocation> toSpecification() {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            var line = root.join("line", JoinType.INNER);
            var order = line.join("order", JoinType.INNER);
            var item = line.join("item", JoinType.INNER);
            var stock = root.join("inventory", JoinType.INNER);
            var stockLpn = stock.join("container", JoinType.LEFT);
            if (q != null && !q.isBlank()) {
                String like = "%" + OrderSearch.escape(q.trim().toLowerCase(Locale.ROOT)) + "%";

                // Where it is picked from (and the LPN) is recorded on the task detail made for it.
                Subquery<Integer> place = query.subquery(Integer.class);
                Root<PickTaskDetail> task = place.from(PickTaskDetail.class);
                var location = task.join("fromLocation", JoinType.INNER);
                var lpn = task.join("fromContainer", JoinType.LEFT);
                place.select(cb.literal(1)).where(cb.or(cb.equal(task.get("allocation"), root),
                        cb.equal(task.get("pickedAllocation"), root)), cb.or(
                        cb.like(cb.lower(location.get("locationId")), like, '\\'),
                        cb.like(cb.lower(lpn.get("lpnId")), like, '\\'),
                        cb.like(cb.lower(stockLpn.get("lpnId")), like, '\\')));

                predicates.add(cb.or(
                        cb.like(cb.lower(order.get("orderNumber")), like, '\\'),
                        cb.like(cb.lower(order.get("customerName")), like, '\\'),
                        cb.like(cb.lower(item.get("itemId")), like, '\\'),
                        cb.like(cb.lower(stock.get("lotNumber")), like, '\\'),
                        cb.like(cb.lower(stock.get("serialNumber")), like, '\\'),
                        cb.exists(place)));
            }
            if (orderStatus != null) {
                predicates.add(cb.equal(order.get("status"), orderStatus));
            }
            if (inventoryStatus != null) {
                predicates.add(cb.equal(stock.get("status"), inventoryStatus));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
