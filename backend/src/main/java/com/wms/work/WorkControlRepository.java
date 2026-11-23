package com.wms.work;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import jakarta.persistence.LockModeType;

interface WorkControlRepository extends Repository<WorkControl, Integer> {

    /** Only one transaction at a time gets past this. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from WorkControl c where c.id = 1")
    WorkControl lock();
}
