package com.wms.outbound;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.wms.users.UserWork;

import jakarta.persistence.EntityManager;

/** Pick and packing tasks someone has in hand. */
@Component
class OutboundUserWork implements UserWork {

    private final EntityManager em;

    OutboundUserWork(EntityManager em) {
        this.em = em;
    }

    @Override
    public List<String> inHand(UUID userId) {
        List<String> work = new ArrayList<>();
        em.createQuery("select t.taskNumber from PickTask t where t.assignedTo.id = :user and t.status in :statuses order by t.taskNumber",
                        String.class)
                .setParameter("user", userId).setParameter("statuses", List.of(PickTaskStatus.IN_PROGRESS, PickTaskStatus.PICKED))
                .getResultList().forEach(n -> work.add("Picking " + n));
        em.createQuery("select t.taskNumber from PackTask t where t.assignedTo.id = :user and t.status = :status order by t.taskNumber",
                        String.class)
                .setParameter("user", userId).setParameter("status", PackTaskStatus.IN_PROGRESS)
                .getResultList().forEach(n -> work.add("Packing " + n));
        return work;
    }
}
