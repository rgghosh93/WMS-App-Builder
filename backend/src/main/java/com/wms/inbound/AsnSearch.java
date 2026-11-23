package com.wms.inbound;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.data.jpa.domain.Specification;

import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

/**
 * Filters for listing ASNs.
 *
 * @param q      free text matched (contains, any case) against ASN number, vendor, reference, and the item and
 *               LPN of its lines and the LPNs received
 * @param status exact status
 */
public record AsnSearch(String q, AsnStatus status) {

    Specification<Asn> toSpecification() {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (q != null && !q.isBlank()) {
                String like = "%" + escape(q.trim().toLowerCase(Locale.ROOT)) + "%";
                Subquery<Integer> inLines = query.subquery(Integer.class);
                Root<AsnLine> line = inLines.from(AsnLine.class);
                inLines.select(cb.literal(1)).where(cb.equal(line.get("asn"), root), cb.or(
                        cb.like(cb.lower(line.join("item", JoinType.INNER).get("itemId")), like, '\\'),
                        cb.like(cb.lower(line.get("lpnId")), like, '\\')));
                Subquery<Integer> inReceipts = query.subquery(Integer.class);
                Root<AsnReceipt> receipt = inReceipts.from(AsnReceipt.class);
                inReceipts.select(cb.literal(1)).where(cb.equal(receipt.get("asn"), root),
                        cb.like(cb.lower(receipt.join("container", JoinType.INNER).get("lpnId")), like, '\\'));
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("asnNumber")), like, '\\'),
                        cb.like(cb.lower(root.get("vendorName")), like, '\\'),
                        cb.like(cb.lower(root.get("reference")), like, '\\'),
                        cb.exists(inLines),
                        cb.exists(inReceipts)));
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
