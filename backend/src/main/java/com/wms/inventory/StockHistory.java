package com.wms.inventory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import jakarta.persistence.criteria.Predicate;

/** Reading the stock history. */
@Component
public class StockHistory {

    private static final List<String> SEARCHED = List.of("itemCode", "lotNumber", "serialNumber", "fromLocation", "fromLpn", "toLocation",
            "toLpn", "reference", "reason", "userName");

    private final StockTransactionRepository transactions;

    StockHistory(StockTransactionRepository transactions) {
        this.transactions = transactions;
    }

    /** @param q free text matched (contains, any case) against the item, lot, serial, places, reference, reason and person */
    public Page<StockTransaction> search(String q, String owner, StockTransaction.Type type, Instant since, Instant before, Pageable page) {
        return transactions.findAll((root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (q != null && !q.isBlank()) {
                String like = "%" + q.trim().toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
                predicates.add(cb.or(SEARCHED.stream().map(f -> cb.like(cb.lower(root.get(f)), like, '\\')).toArray(Predicate[]::new)));
            }
            if (owner != null) {
                predicates.add(cb.equal(root.get("ownerCode"), owner));
            }
            if (type != null) {
                predicates.add(cb.equal(root.get("type"), type));
            }
            if (since != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("occurredAt"), since));
            }
            if (before != null) {
                predicates.add(cb.lessThan(root.get("occurredAt"), before));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        }, page);
    }
}
