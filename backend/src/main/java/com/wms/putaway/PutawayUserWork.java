package com.wms.putaway;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.wms.users.UserWork;

/** The LPN someone is putting away. */
@Component
class PutawayUserWork implements UserWork {

    private final PutawayTaskRepository tasks;

    PutawayUserWork(PutawayTaskRepository tasks) {
        this.tasks = tasks;
    }

    @Override
    public List<String> inHand(UUID userId) {
        return tasks.findFirstByAssignedTo_IdAndStatus(userId, PutawayTaskStatus.IN_PROGRESS)
                .map(t -> List.of("Putting away " + t.getContainer().getLpnId())).orElse(List.of());
    }
}
