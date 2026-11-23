package com.wms.outbound;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.data.jpa.domain.Specification;

import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

/**
 * Filters for listing manifests.
 *
 * @param q      free text matched (contains, any case) against manifest number, carrier, door, trailer, and the
 *               shipment and order numbers on it
 * @param status exact status
 */
public record ManifestSearch(String q, ManifestStatus status) {

    Specification<Manifest> toSpecification() {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (q != null && !q.isBlank()) {
                String like = "%" + OrderSearch.escape(q.trim().toLowerCase(Locale.ROOT)) + "%";
                Subquery<Integer> inShipments = query.subquery(Integer.class);
                Root<Shipment> shipment = inShipments.from(Shipment.class);
                inShipments.select(cb.literal(1)).where(cb.equal(shipment.get("manifest"), root), cb.or(
                        cb.like(cb.lower(shipment.get("shipmentNumber")), like, '\\'),
                        cb.like(cb.lower(shipment.join("order", JoinType.INNER).get("orderNumber")), like, '\\')));
                var carrier = root.join("carrier", JoinType.INNER);
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("manifestNumber")), like, '\\'),
                        cb.like(cb.lower(carrier.get("carrierCode")), like, '\\'),
                        cb.like(cb.lower(carrier.get("name")), like, '\\'),
                        cb.like(cb.lower(root.join("door", JoinType.INNER).get("locationId")), like, '\\'),
                        cb.like(cb.lower(root.get("trailerNumber")), like, '\\'),
                        cb.exists(inShipments)));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
