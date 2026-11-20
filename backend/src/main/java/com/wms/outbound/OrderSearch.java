package com.wms.outbound;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.data.jpa.domain.Specification;

import jakarta.persistence.criteria.Predicate;

/**
 * Filters for listing orders.
 *
 * @param q      free text matched (contains, any case) against order number and customer
 * @param status exact status
 */
public record OrderSearch(String q, OrderStatus status) {

    Specification<OutboundOrder> toSpecification() {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (q != null && !q.isBlank()) {
                String like = "%" + escape(q.trim().toLowerCase(Locale.ROOT)) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("orderNumber")), like, '\\'),
                        cb.like(cb.lower(root.get("customerName")), like, '\\')));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    static String escape(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
