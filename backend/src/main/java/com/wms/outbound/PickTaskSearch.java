package com.wms.outbound;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.data.jpa.domain.Specification;

import com.wms.common.Codes;

import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

/**
 * Filters for listing pick tasks.
 *
 * @param q          free text matched (contains, any case) against task number, outbound container, picker, and the order
 *                   number, item, from-location and LPN of any of the task's details
 * @param status     exact task status
 * @param pickMethod discrete or batch
 * @param pickZone   code of the pick zone
 */
public record PickTaskSearch(String q, PickTaskStatus status, PickMethod pickMethod, String pickZone) {

    Specification<PickTask> toSpecification() {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (q != null && !q.isBlank()) {
                String like = "%" + OrderSearch.escape(q.trim().toLowerCase(Locale.ROOT)) + "%";

                Subquery<Integer> inDetails = query.subquery(Integer.class);
                Root<PickTaskDetail> detail = inDetails.from(PickTaskDetail.class);
                var item = detail.join("item", JoinType.INNER);
                var order = detail.join("order", JoinType.INNER);
                var location = detail.join("fromLocation", JoinType.INNER);
                var lpn = detail.join("fromContainer", JoinType.LEFT);
                inDetails.select(cb.literal(1)).where(cb.equal(detail.get("task"), root), cb.or(
                        cb.like(cb.lower(order.get("orderNumber")), like, '\\'),
                        cb.like(cb.lower(item.get("itemId")), like, '\\'),
                        cb.like(cb.lower(location.get("locationId")), like, '\\'),
                        cb.like(cb.lower(lpn.get("lpnId")), like, '\\')));

                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("taskNumber")), like, '\\'),
                        cb.like(cb.lower(root.join("toContainer", JoinType.LEFT).get("lpnId")), like, '\\'),
                        cb.like(cb.lower(root.join("assignedTo", JoinType.LEFT).get("displayName")), like, '\\'),
                        cb.exists(inDetails)));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (pickMethod != null) {
                predicates.add(cb.equal(root.get("pickMethod"), pickMethod));
            }
            if (pickZone != null && !pickZone.isBlank()) {
                predicates.add(cb.equal(root.join("pickZone", JoinType.INNER).get("code"), Codes.normalize(pickZone)));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
