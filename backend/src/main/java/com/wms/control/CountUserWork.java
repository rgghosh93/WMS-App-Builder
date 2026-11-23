package com.wms.control;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.wms.users.UserWork;

/** The location someone is counting. */
@Component
class CountUserWork implements UserWork {

    private final CountTaskRepository counts;

    CountUserWork(CountTaskRepository counts) {
        this.counts = counts;
    }

    @Override
    public List<String> inHand(UUID userId) {
        return counts.findFirstByCounterIdAndStatus(userId, CountTask.Status.COUNTING)
                .map(t -> List.of("Counting " + t.getLocation().getLocationId())).orElse(List.of());
    }
}
