package com.wms.replenishment;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.wms.common.ActiveStatus;
import com.wms.inventory.WaitingOrders;

/**
 * Goes through the rules, each in a transaction of its own, so one that fails doesn't hold up the rest.
 * Everything that changes stock is caught by doing this often, rather than by each telling it.
 */
@Component
public class ReplenishmentRun {

    private static final Logger log = LoggerFactory.getLogger(ReplenishmentRun.class);

    private final ReplenishmentRuleRepository rules;
    private final Replenisher replenisher;
    private final WaitingOrders orders;

    ReplenishmentRun(ReplenishmentRuleRepository rules, Replenisher replenisher, WaitingOrders orders) {
        this.rules = rules;
        this.replenisher = replenisher;
        this.orders = orders;
    }

    /**
     * @param quantity eaches tasks were made for
     * @param still    rules that need more than there is to bring
     */
    public record Result(int rules, int quantity, int still) {
    }

    public Result all() {
        return run(rules.findByStatusOrderByLocation_LocationIdAscItem_ItemIdAsc(ActiveStatus.ACTIVE));
    }

    /** After stock was moved: orders waiting for it get it, and what still needs filling is planned. */
    public void afterMove(UUID itemId) {
        try {
            for (UUID order : orders.waitingFor(itemId)) {
                orders.tryAgain(order, itemId);
            }
            run(rules.findByItem_IdAndStatusOrderByLocation_LocationIdAsc(itemId, ActiveStatus.ACTIVE));
        } catch (RuntimeException e) {
            // The move is done all the same, and the next run tries again.
            log.warn("Could not follow up the replenishment of item {}", itemId, e);
        }
    }

    private Result run(List<ReplenishmentRule> which) {
        int quantity = 0;
        int still = 0;
        for (ReplenishmentRule rule : which) {
            try {
                replenisher.tidy(rule.getId());
                if (replenisher.level(rule.getId()).needed() > 0) {
                    quantity += replenisher.plan(rule.getId());
                    if (replenisher.level(rule.getId()).needed() > 0) {
                        still++;
                    }
                }
            } catch (RuntimeException e) {
                log.warn("Could not work out replenishment rule {}", rule.getId(), e);
            }
        }
        return new Result(which.size(), quantity, still);
    }

    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(name = "wms.replenishment.automatic", havingValue = "true", matchIfMissing = true)
    static class Automatic {
        private final ReplenishmentRun run;

        Automatic(ReplenishmentRun run) {
            this.run = run;
        }

        @Scheduled(fixedDelayString = "${wms.replenishment.interval-seconds:60}", initialDelayString = "${wms.replenishment.interval-seconds:60}",
                timeUnit = TimeUnit.SECONDS)
        void often() {
            Result result = run.all();
            if (result.quantity() > 0) {
                log.info("Planned replenishment of {} EA", result.quantity());
            }
        }
    }
}
