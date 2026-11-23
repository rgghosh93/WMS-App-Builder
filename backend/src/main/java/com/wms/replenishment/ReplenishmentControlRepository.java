package com.wms.replenishment;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import jakarta.persistence.LockModeType;

interface ReplenishmentControlRepository extends Repository<ReplenishmentControl, Integer> {

    /** Only one transaction at a time gets past this. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ReplenishmentControl c where c.id = 1")
    ReplenishmentControl lock();
}
