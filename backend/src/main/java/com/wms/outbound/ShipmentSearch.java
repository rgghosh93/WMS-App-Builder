package com.wms.outbound;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.data.jpa.domain.Specification;

import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

/**
 * Filters for listing shipments.
 *
 * @param q         free text matched (contains, any case) against shipment, order and manifest number,
 *                  customer, and the LPN and tracking number of its cartons
 * @param status    exact status
 * @param carrierId only shipments going with this carrier
 */
public record ShipmentSearch(String q, ShipmentStatus status, UUID carrierId) {

    Specification<Shipment> toSpecification() {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (q != null && !q.isBlank()) {
                String like = "%" + OrderSearch.escape(q.trim().toLowerCase(Locale.ROOT)) + "%";
                Subquery<Integer> inCartons = query.subquery(Integer.class);
                Root<PackCarton> carton = inCartons.from(PackCarton.class);
                inCartons.select(cb.literal(1)).where(cb.equal(carton.get("shipment"), root), cb.or(
                        cb.like(cb.lower(carton.join("container", JoinType.INNER).get("lpnId")), like, '\\'),
                        cb.like(cb.lower(carton.get("trackingNumber")), like, '\\')));
                var order = root.join("order", JoinType.INNER);
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("shipmentNumber")), like, '\\'),
                        cb.like(cb.lower(order.get("orderNumber")), like, '\\'),
                        cb.like(cb.lower(order.get("customerName")), like, '\\'),
                        cb.like(cb.lower(root.join("manifest", JoinType.LEFT).get("manifestNumber")), like, '\\'),
                        cb.exists(inCartons)));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (carrierId != null) {
                predicates.add(cb.equal(root.join("carrierService", JoinType.INNER).get("carrier").get("id"), carrierId));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
