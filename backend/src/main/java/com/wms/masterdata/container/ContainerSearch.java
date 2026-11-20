package com.wms.masterdata.container;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.data.jpa.domain.Specification;

import jakarta.persistence.criteria.Predicate;

/**
 * Filters for listing containers.
 *
 * @param q               free text matched (contains, any case) against the LPN
 * @param containerTypeId exact container type (internal id)
 * @param status          exact status
 * @param locationId      exact current location (internal id)
 * @param permanent       true = facility-owned containers only, false = inventory LPNs only, null = both
 */
public record ContainerSearch(String q, UUID containerTypeId, ContainerStatus status, UUID locationId, Boolean permanent) {

    Specification<Container> toSpecification() {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (q != null && !q.isBlank()) {
                String like = "%" + escape(q.trim().toLowerCase(Locale.ROOT)) + "%";
                predicates.add(cb.like(cb.lower(root.get("lpnId")), like, '\\'));
            }
            if (containerTypeId != null) {
                predicates.add(cb.equal(root.get("containerType").get("id"), containerTypeId));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (locationId != null) {
                predicates.add(cb.equal(root.get("currentLocation").get("id"), locationId));
            }
            if (permanent != null) {
                predicates.add(cb.equal(root.get("permanent"), permanent));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
