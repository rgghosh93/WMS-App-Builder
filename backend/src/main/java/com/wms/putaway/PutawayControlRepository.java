package com.wms.putaway;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import jakarta.persistence.LockModeType;

interface PutawayControlRepository extends Repository<PutawayControl, Integer> {

    /** Only one transaction at a time gets past this. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from PutawayControl c where c.id = 1")
    PutawayControl lock();
}
