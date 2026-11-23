package com.wms.putaway;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.data.jpa.domain.Specification;

import com.wms.inventory.InventoryRecord;

import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

/**
 * Filters for listing put-away tasks.
 *
 * @param q free text matched (contains, any case) against task number, LPN, locations, and the items in it
 */
public record PutawaySearch(String q, PutawayTaskStatus status) {

    Specification<PutawayTask> toSpecification() {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (q != null && !q.isBlank()) {
                String like = "%" + escape(q.trim().toLowerCase(Locale.ROOT)) + "%";
                Subquery<Integer> done = query.subquery(Integer.class);
                Root<PutawayTaskLine> line = done.from(PutawayTaskLine.class);
                done.select(cb.literal(1)).where(cb.equal(line.get("task"), root),
                        cb.like(cb.lower(line.join("item", JoinType.INNER).get("itemId")), like, '\\'));
                Subquery<Integer> waiting = query.subquery(Integer.class);
                Root<InventoryRecord> stock = waiting.from(InventoryRecord.class);
                waiting.select(cb.literal(1)).where(cb.equal(stock.get("container"), root.get("container")),
                        root.get("status").in(PutawayTaskStatus.OPEN, PutawayTaskStatus.IN_PROGRESS),
                        cb.like(cb.lower(stock.join("item", JoinType.INNER).get("itemId")), like, '\\'));
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("taskNumber")), like, '\\'),
                        cb.like(cb.lower(root.join("container", JoinType.INNER).get("lpnId")), like, '\\'),
                        cb.like(cb.lower(root.join("fromLocation", JoinType.INNER).get("locationId")), like, '\\'),
                        cb.like(cb.lower(root.join("directedLocation", JoinType.LEFT).get("locationId")), like, '\\'),
                        cb.like(cb.lower(root.join("toLocation", JoinType.LEFT).get("locationId")), like, '\\'),
                        cb.exists(done),
                        cb.exists(waiting)));
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
