package com.wms.outbound;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.wms.common.ActiveStatus;
import com.wms.facility.Facility;
import com.wms.facility.FacilityRepository;
import com.wms.facility.Site;
import com.wms.inventory.StockFreed;
import com.wms.inventory.WaitingOrders;

/**
 * Gets stock for orders that tried to and are short of some, most urgent order first, each in a transaction of its own.
 * Orders never allocated, and those taken back to New, are left for someone to allocate.
 */
@Component
public class WaitingOrderRun {

    private static final Logger log = LoggerFactory.getLogger(WaitingOrderRun.class);

    private final WaitingOrders orders;

    WaitingOrderRun(WaitingOrders orders) {
        this.orders = orders;
    }

    /** Every item that orders are waiting for. */
    public void all() {
        orders.itemsWaitedFor().forEach(this::item);
    }

    public void item(UUID itemId) {
        for (UUID order : orders.waitingFor(itemId)) {
            try {
                if (!orders.tryAgain(order, itemId)) {
                    return; // there is none left for those after it
                }
            } catch (RuntimeException e) {
                // Someone else is changing it. The next run tries again.
                log.warn("Could not allocate item {} to waiting order {}: {}", itemId, order, e.getMessage());
            }
        }
    }

    /**
     * Does it by itself: straight after something frees stock of an item, and every so often for what nothing tells of,
     * such as a zone changed or a count finished. On a thread of its own, so whoever freed the stock doesn't wait for it.
     */
    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(name = "wms.orders.allocate-waiting", havingValue = "true", matchIfMissing = true)
    static class Automatic implements DisposableBean {
        private final WaitingOrderRun run;
        private final FacilityRepository facilities;
        private final Set<StockFreed> queued = ConcurrentHashMap.newKeySet();
        private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "waiting-orders");
            thread.setDaemon(true);
            return thread;
        });

        Automatic(WaitingOrderRun run, FacilityRepository facilities) {
            this.run = run;
            this.facilities = facilities;
        }

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        void freed(StockFreed stock) {
            if (queued.add(stock)) {
                worker.execute(() -> {
                    // Taken off first: what is freed while this runs has to be looked at again.
                    queued.remove(stock);
                    safely(stock.facilityId(), () -> run.item(stock.itemId()));
                });
            }
        }

        @Scheduled(fixedDelayString = "${wms.orders.waiting-interval-seconds:60}", initialDelayString = "${wms.orders.waiting-interval-seconds:60}",
                timeUnit = TimeUnit.SECONDS)
        void often() {
            List<Facility> all = facilities.findByStatusOrderByCodeAsc(ActiveStatus.ACTIVE);
            worker.execute(() -> all.forEach(f -> safely(f.getId(), run::all)));
        }

        private static void safely(UUID facilityId, Runnable work) {
            try {
                Site.in(facilityId, () -> {
                    work.run();
                    return null;
                });
            } catch (RuntimeException e) {
                log.warn("Could not allocate waiting orders", e);
            }
        }

        @Override
        public void destroy() {
            worker.shutdownNow();
        }
    }
}
