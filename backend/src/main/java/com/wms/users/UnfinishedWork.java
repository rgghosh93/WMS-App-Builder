package com.wms.users;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** What someone has started and not finished, in the facility the caller is in. */
@Service
class UnfinishedWork {

    private final List<UserWork> work;

    UnfinishedWork(List<UserWork> work) {
        this.work = work;
    }

    @Transactional(readOnly = true)
    public List<String> of(UUID userId) {
        return work.stream().filter(UserWork::halfDone).flatMap(w -> w.inHand(userId).stream()).toList();
    }
}
