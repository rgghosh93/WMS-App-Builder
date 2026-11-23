package com.wms.inventory;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/** The history can be added to and read, never changed. */
interface StockTransactionRepository extends Repository<StockTransaction, UUID>,
        org.springframework.data.jpa.repository.JpaSpecificationExecutor<StockTransaction> {

    StockTransaction save(StockTransaction transaction);

    @Override
    Page<StockTransaction> findAll(Specification<StockTransaction> spec, Pageable pageable);

    Optional<StockTransaction> findFirstByUserIdOrderByNumberDesc(UUID userId);

    boolean existsByRequestId(UUID requestId);

    @Query(value = "select nextval('inventory_transaction_seq')", nativeQuery = true)
    long nextNumber();
}
