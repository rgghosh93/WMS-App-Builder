package com.wms.masterdata.location;

import com.wms.common.Codes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.data.jpa.domain.Specification;

import jakarta.persistence.criteria.Predicate;

/**
 * Filters for listing locations.
 *
 * @param q    free text matched (contains, any case) against the location ID and every identifier
 * @param type exact location type
 * @param area exact area
 * @param zone exact zone
 */
public record LocationSearch(String q, LocationType type, String area, String zone) {

    Specification<Location> toSpecification() {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (q != null && !q.isBlank()) {
                String like = "%" + escape(q.trim().toLowerCase(Locale.ROOT)) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("locationId")), like, '\\'),
                        cb.like(cb.lower(root.get("area")), like, '\\'),
                        cb.like(cb.lower(root.get("zone")), like, '\\'),
                        cb.like(cb.lower(root.get("aisle")), like, '\\'),
                        cb.like(cb.lower(root.get("bay")), like, '\\'),
                        cb.like(cb.lower(root.get("level")), like, '\\'),
                        cb.like(cb.lower(root.get("position")), like, '\\')));
            }
            if (type != null) {
                predicates.add(cb.equal(root.get("type"), type));
            }
            if (area != null && !area.isBlank()) {
                predicates.add(cb.equal(root.get("area"), Codes.normalize(area)));
            }
            if (zone != null && !zone.isBlank()) {
                predicates.add(cb.equal(root.get("zone"), Codes.normalize(zone)));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
