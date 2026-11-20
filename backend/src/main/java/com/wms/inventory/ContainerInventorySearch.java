package com.wms.inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.data.jpa.domain.Specification;

import com.wms.masterdata.container.Container;
import com.wms.masterdata.container.ContainerCategory;

import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

/**
 * Filters for the container list in the inventory view.
 *
 * @param q            free text matched (contains, any case) against the LPN
 * @param category     exact container category
 * @param permanent    true = facility-owned containers, false = inventory LPNs, null = both
 * @param withStockOnly only containers holding stock, directly or in containers inside them
 */
public record ContainerInventorySearch(String q, ContainerCategory category, Boolean permanent, boolean withStockOnly) {

    /** How deep the "holds stock" filter looks through containers inside containers. */
    static final int STOCK_SEARCH_DEPTH = 6;

    Specification<Container> toSpecification() {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (q != null && !q.isBlank()) {
                String like = "%" + escape(q.trim().toLowerCase(Locale.ROOT)) + "%";
                predicates.add(cb.like(cb.lower(root.get("lpnId")), like, '\\'));
            }
            if (category != null) {
                predicates.add(cb.equal(root.get("containerType").get("category"), category));
            }
            if (permanent != null) {
                predicates.add(cb.equal(root.get("permanent"), permanent));
            }
            if (withStockOnly) {
                // Stock whose container is this one, or whose container sits (up to N levels) inside this one.
                Subquery<Integer> stock = query.subquery(Integer.class);
                Root<InventoryRecord> record = stock.from(InventoryRecord.class);
                Join<InventoryRecord, Container> holder = record.join("container");
                List<Predicate> holds = new ArrayList<>();
                holds.add(cb.equal(holder, root));
                Join<?, Container> ancestor = holder;
                for (int level = 1; level < STOCK_SEARCH_DEPTH; level++) {
                    ancestor = ancestor.join("parent", JoinType.LEFT);
                    holds.add(cb.equal(ancestor, root));
                }
                stock.select(cb.literal(1)).where(cb.or(holds.toArray(Predicate[]::new)));
                predicates.add(cb.exists(stock));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
