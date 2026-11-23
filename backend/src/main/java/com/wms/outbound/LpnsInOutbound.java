package com.wms.outbound;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.wms.inventory.BusyLpns;

/** Containers being picked into, packed out of, or waiting to ship. */
@Component
class LpnsInOutbound implements BusyLpns {

    private final PickTaskRepository picks;
    private final PackTaskRepository packs;
    private final PackCartonRepository cartons;

    LpnsInOutbound(PickTaskRepository picks, PackTaskRepository packs, PackCartonRepository cartons) {
        this.picks = picks;
        this.packs = packs;
        this.cartons = cartons;
    }

    @Override
    public Optional<String> busyWith(UUID containerId) {
        return picks.findNumberActiveInto(containerId).map(n -> "is being picked into (" + n + ")")
                .or(() -> packs.findOpenByContainer(containerId).map(t -> "is waiting to be packed (" + t.getTaskNumber() + ")"))
                .or(() -> cartons.findLiveOrders(containerId).stream().findFirst().map(o -> "is packed for " + o));
    }
}
