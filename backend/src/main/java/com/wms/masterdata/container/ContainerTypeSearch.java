package com.wms.masterdata.container;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.data.jpa.domain.Specification;

import com.wms.common.ActiveStatus;

import jakarta.persistence.criteria.Predicate;

/**
 * Filters for listing container types.
 *
 * @param q        free text matched (contains, any case) against ID and description
 * @param category exact category
 * @param status   exact status
 */
public record ContainerTypeSearch(String q, ContainerCategory category, ActiveStatus status) {

    Specification<ContainerType> toSpecification() {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (q != null && !q.isBlank()) {
                String like = "%" + escape(q.trim().toLowerCase(Locale.ROOT)) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("containerTypeId")), like, '\\'),
                        cb.like(cb.lower(root.get("description")), like, '\\')));
            }
            if (category != null) {
                predicates.add(cb.equal(root.get("category"), category));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
