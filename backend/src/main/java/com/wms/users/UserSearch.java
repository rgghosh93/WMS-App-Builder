package com.wms.users;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.data.jpa.domain.Specification;

import com.wms.common.ActiveStatus;

import jakarta.persistence.criteria.Predicate;

/**
 * Filters for listing users.
 *
 * @param q free text matched (contains, any case) against user ID and name
 */
public record UserSearch(String q, ActiveStatus status) {

    Specification<AppUser> toSpecification() {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (q != null && !q.isBlank()) {
                String like = "%" + escape(q.trim().toLowerCase(Locale.ROOT)) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("userCode")), like, '\\'),
                        cb.like(cb.lower(root.get("displayName")), like, '\\')));
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
