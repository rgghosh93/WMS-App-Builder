package com.wms.outbound;

import java.time.LocalDate;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.wms.masterdata.item.ItemRepository;

/**
 * Sends a few demo orders through the normal order intake when there are none
 * (local dev only), so allocation and pick tasks run for real against the demo stock:
 * <ul>
 *   <li>Northwind Hardware: fully allocated from bins, shelves and a pallet of serial-tracked drills</li>
 *   <li>Bluebird Grocers: protein bars short (27 of 30); water from the earliest-expiring lot first</li>
 *   <li>Cascade Pharmacy: vitamins from the earlier-expiring lot</li>
 *   <li>Summit Outfitters: a specific granola lot that is on quality hold, so backordered</li>
 *   <li>Harbor Cafe and Pine Street Deli: small orders picked together on one batch task</li>
 * </ul>
 * Runs after the demo inventory loader.
 */
@Component
@ConditionalOnProperty(name = "wms.demo-data.enabled", havingValue = "true")
@Order(40)
public class DemoOrderLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoOrderLoader.class);

    private final OrderRepository orders;
    private final OrderService orderService;
    private final ItemRepository items;

    public DemoOrderLoader(OrderRepository orders, OrderService orderService, ItemRepository items) {
        this.orders = orders;
        this.orderService = orderService;
        this.items = items;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (orders.count() > 0 || items.findByItemId("SKU-10001").isEmpty()) {
            return;
        }
        LocalDate today = LocalDate.now();
        List<OrderRequest> demo = List.of(
                new OrderRequest(null, "Northwind Hardware", today.plusDays(1), 3, List.of(
                        line("SKU-10003", 24, null),
                        line("SKU-10004", 30, null),
                        line("SKU-10001", 3, null))),
                new OrderRequest(null, "Bluebird Grocers", today.plusDays(2), 5, List.of(
                        line("SKU-20001", 30, null),
                        line("SKU-20002", 50, null))),
                new OrderRequest(null, "Cascade Pharmacy", today.plusDays(1), 2, List.of(
                        line("SKU-30001", 20, null),
                        line("SKU-30002", 6, null))),
                new OrderRequest(null, "Summit Outfitters", today.plusDays(4), 7, List.of(
                        line("SKU-20004", 10, "GR2608"))));
        demo.forEach(orderService::create);

        List<OrderRequest> batch = List.of(
                new OrderRequest(null, "Harbor Cafe", today.plusDays(1), 4, List.of(
                        line("SKU-20002", 12, null),
                        line("SKU-30002", 2, null))),
                new OrderRequest(null, "Pine Street Deli", today.plusDays(1), 4, List.of(
                        line("SKU-20002", 6, null),
                        line("SKU-10004", 4, null))));
        orderService.allocateBatch(batch.stream().map(r -> orderService.create(r, false).id()).toList());
        log.info("Loaded {} demo orders", demo.size() + batch.size());
    }

    private static OrderRequest.Line line(String itemId, int quantity, String lot) {
        return new OrderRequest.Line(itemId, quantity, lot);
    }
}
