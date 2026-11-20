package com.wms.masterdata.item;

import com.wms.common.ActiveStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.data.jpa.domain.Specification;

import jakarta.persistence.criteria.Predicate;

/**
 * Filters for listing items.
 *
 * @param q      free text matched (contains, any case) against item ID, description and barcode
 * @param status exact status
 */
public record ItemSearch(String q, ActiveStatus status) {

    Specification<Item> toSpecification() {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (q != null && !q.isBlank()) {
                String like = "%" + escape(q.trim().toLowerCase(Locale.ROOT)) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("itemId")), like, '\\'),
                        cb.like(cb.lower(root.get("description")), like, '\\'),
                        cb.like(cb.lower(root.get("barcode")), like, '\\')));
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
