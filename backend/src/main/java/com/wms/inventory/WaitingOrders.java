package com.wms.inventory;

import java.util.List;
import java.util.UUID;

/** Orders that tried to get stock and are still short of some (provided by the outbound flow). */
public interface WaitingOrders {

    /** Eaches of an item they are short of, any lot. */
    int shortOf(UUID itemId);

    /** Orders short of an item, most urgent first. */
    List<UUID> waitingFor(UUID itemId);

    /** Tries again to get an order stock of an item. */
    void tryAgain(UUID orderId, UUID itemId);
}
