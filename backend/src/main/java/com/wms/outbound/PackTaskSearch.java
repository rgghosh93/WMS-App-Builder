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
 * Filters for listing packing tasks.
 *
 * @param q         free text matched (contains, any case) against task number, container, packer, and the
 *                  order number, customer, item and carton of anything in the task
 * @param status    exact task status
 * @param stationId only tasks at this pack station
 */
public record PackTaskSearch(String q, PackTaskStatus status, UUID stationId) {

    Specification<PackTask> toSpecification() {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (q != null && !q.isBlank()) {
                String like = "%" + OrderSearch.escape(q.trim().toLowerCase(Locale.ROOT)) + "%";

                Subquery<Integer> inDetails = query.subquery(Integer.class);
                Root<PackTaskDetail> detail = inDetails.from(PackTaskDetail.class);
                var order = detail.join("order", JoinType.INNER);
                var item = detail.join("item", JoinType.INNER);
                inDetails.select(cb.literal(1)).where(cb.equal(detail.get("task"), root), cb.or(
                        cb.like(cb.lower(order.get("orderNumber")), like, '\\'),
                        cb.like(cb.lower(order.get("customerName")), like, '\\'),
                        cb.like(cb.lower(item.get("itemId")), like, '\\')));

                Subquery<Integer> inCartons = query.subquery(Integer.class);
                Root<PackCarton> carton = inCartons.from(PackCarton.class);
                inCartons.select(cb.literal(1)).where(cb.equal(carton.get("task"), root),
                        cb.like(cb.lower(carton.join("container", JoinType.INNER).get("lpnId")), like, '\\'));

                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("taskNumber")), like, '\\'),
                        cb.like(cb.lower(root.join("container", JoinType.INNER).get("lpnId")), like, '\\'),
                        cb.like(cb.lower(root.join("assignedTo", JoinType.LEFT).get("displayName")), like, '\\'),
                        cb.exists(inDetails),
                        cb.exists(inCartons)));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (stationId != null) {
                predicates.add(cb.equal(root.get("station").get("id"), stationId));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
