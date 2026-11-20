package com.wms.inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.data.jpa.domain.Specification;

import com.wms.common.Codes;
import com.wms.masterdata.container.Container;
import com.wms.masterdata.location.Location;

import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Subquery;

/**
 * Filters for the location list in the inventory view.
 *
 * @param q            free text matched (contains, any case) against the location ID
 * @param zone         exact zone
 * @param occupiedOnly only locations holding stock or containers
 */
public record InventoryLocationSearch(String q, String zone, boolean occupiedOnly) {

    Specification<Location> toSpecification() {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (q != null && !q.isBlank()) {
                String like = "%" + escape(q.trim().toLowerCase(Locale.ROOT)) + "%";
                predicates.add(cb.like(cb.lower(root.get("locationId")), like, '\\'));
            }
            if (zone != null && !zone.isBlank()) {
                predicates.add(cb.equal(root.get("zone"), Codes.normalize(zone)));
            }
            if (occupiedOnly) {
                Subquery<Integer> stock = query.subquery(Integer.class);
                var inventory = stock.from(InventoryRecord.class);
                stock.select(cb.literal(1)).where(cb.equal(inventory.get("location"), root));

                Subquery<Integer> containers = query.subquery(Integer.class);
                var container = containers.from(Container.class);
                containers.select(cb.literal(1)).where(cb.equal(container.get("currentLocation"), root));

                predicates.add(cb.or(cb.exists(stock), cb.exists(containers)));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
