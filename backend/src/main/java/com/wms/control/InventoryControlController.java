package com.wms.control;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.wms.common.api.PageResponse;
import com.wms.control.AdjustmentService.AdjustmentView;
import com.wms.control.ControlViews.HoldResult;
import com.wms.control.ControlViews.ItemOnHand;
import com.wms.control.ControlViews.StockView;
import com.wms.control.ControlViews.TransactionView;
import com.wms.control.CountViews.CountDetail;
import com.wms.control.CountViews.CountSummary;
import com.wms.control.CountViews.Planned;
import com.wms.control.ReasonService.ReasonView;
import com.wms.control.ReasonService.SettingsView;
import com.wms.inventory.StockTransaction;
import com.wms.users.DesktopUser;
import com.wms.users.Permission;
import com.wms.users.Requires;

import jakarta.validation.Valid;

/** Inventory control on the desktop. */
@RestController
@RequestMapping("/api/inventory")
public class InventoryControlController {

    private static final int MAX_PAGE_SIZE = 200;

    private final StockQueryService stock;
    private final HoldService holds;
    private final AdjustmentService adjustments;
    private final CountService counts;
    private final ReasonService reasons;

    public InventoryControlController(StockQueryService stock, HoldService holds, AdjustmentService adjustments, CountService counts,
                                      ReasonService reasons) {
        this.stock = stock;
        this.holds = holds;
        this.adjustments = adjustments;
        this.counts = counts;
        this.reasons = reasons;
    }

    // ---------- Looking ----------

    @GetMapping("/stock")
    public PageResponse<StockView> stock(@RequestParam(required = false) String q, @RequestParam(defaultValue = "false") boolean held,
                                         @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return PageResponse.of(stock.stock(q, held, page(page, size)), s -> s);
    }

    @GetMapping("/on-hand")
    public PageResponse<ItemOnHand> onHand(@RequestParam(required = false) String q, @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "25") int size) {
        return PageResponse.of(stock.onHand(q, page(page, size)), i -> i);
    }

    @GetMapping("/history")
    public PageResponse<TransactionView> history(@RequestParam(required = false) String q,
                                                 @RequestParam(required = false) StockTransaction.Type type,
                                                 @RequestParam(required = false) LocalDate from,
                                                 @RequestParam(required = false) LocalDate to,
                                                 @RequestParam(defaultValue = "0") int page,
                                                 @RequestParam(defaultValue = "25") int size) {
        PageRequest p = page(page, size);
        return PageResponse.of(stock.history(q, type, from, to, p.getPageNumber(), p.getPageSize()), t -> t);
    }

    // ---------- Holds ----------

    @PostMapping("/holds/preview")
    @Requires(Permission.INVENTORY)
    public HoldResult previewHold(@RequestBody HoldService.Scope scope) {
        return holds.preview(scope);
    }

    @PostMapping("/holds")
    @Requires(Permission.INVENTORY)
    public HoldResult hold(@RequestAttribute(DesktopUser.ATTRIBUTE) DesktopUser user, @RequestBody HoldService.Hold request) {
        return holds.hold(Actor.of(user), request);
    }

    @PostMapping("/holds/release")
    @Requires(Permission.INVENTORY)
    public HoldResult release(@RequestAttribute(DesktopUser.ATTRIBUTE) DesktopUser user, @RequestBody HoldService.Release request) {
        return holds.release(Actor.of(user), request);
    }

    // ---------- Adjustments ----------

    @GetMapping("/adjustments")
    public PageResponse<AdjustmentView> adjustments(@RequestParam(required = false) String q,
                                                    @RequestParam(required = false) Adjustment.Status status,
                                                    @RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "25") int size) {
        return PageResponse.of(adjustments.search(q, status, page(page, size, "requestedAt", "number")), a -> a);
    }

    @PostMapping("/adjustments/change")
    @Requires(Permission.INVENTORY)
    public AdjustmentView change(@RequestAttribute(DesktopUser.ATTRIBUTE) DesktopUser user, @RequestBody AdjustmentService.Change request) {
        return adjustments.change(Actor.of(user), request);
    }

    @PostMapping("/adjustments/add")
    @Requires(Permission.INVENTORY)
    public AdjustmentView add(@RequestAttribute(DesktopUser.ATTRIBUTE) DesktopUser user, @RequestBody AdjustmentService.Add request) {
        return adjustments.add(Actor.of(user), request);
    }

    @PostMapping("/adjustments/{id}/approve")
    @Requires(Permission.INVENTORY_APPROVE)
    public AdjustmentView approve(@RequestAttribute(DesktopUser.ATTRIBUTE) DesktopUser user, @PathVariable UUID id) {
        return adjustments.approve(Actor.of(user), id);
    }

    @PostMapping("/adjustments/{id}/reject")
    @Requires(Permission.INVENTORY_APPROVE)
    public AdjustmentView reject(@RequestAttribute(DesktopUser.ATTRIBUTE) DesktopUser user, @PathVariable UUID id) {
        return adjustments.reject(Actor.of(user), id);
    }

    // ---------- Counts ----------

    @GetMapping("/counts")
    public PageResponse<CountSummary> counts(@RequestParam(required = false) String q,
                                             @RequestParam(required = false) CountTask.Status status,
                                             @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return PageResponse.of(counts.search(q, status, page(page, size, "plannedAt", "number")), c -> c);
    }

    @GetMapping("/counts/{id}")
    public CountDetail count(@PathVariable UUID id) {
        return counts.get(id);
    }

    @PostMapping("/counts")
    @Requires(Permission.INVENTORY)
    public Planned plan(@RequestAttribute(DesktopUser.ATTRIBUTE) DesktopUser user, @RequestBody CountService.Plan plan) {
        return counts.plan(Actor.of(user), plan);
    }

    @PostMapping("/counts/{id}/approve")
    @Requires(Permission.INVENTORY_APPROVE)
    public CountDetail approveCount(@RequestAttribute(DesktopUser.ATTRIBUTE) DesktopUser user, @PathVariable UUID id) {
        return counts.approve(Actor.of(user), id);
    }

    @PostMapping("/counts/{id}/recount")
    @Requires(Permission.INVENTORY)
    public CountDetail recount(@RequestAttribute(DesktopUser.ATTRIBUTE) DesktopUser user, @PathVariable UUID id) {
        return counts.recount(Actor.of(user), id);
    }

    @PostMapping("/counts/{id}/cancel")
    @Requires(Permission.INVENTORY)
    public CountDetail cancelCount(@RequestAttribute(DesktopUser.ATTRIBUTE) DesktopUser user, @PathVariable UUID id) {
        return counts.cancel(Actor.of(user), id);
    }

    // ---------- Reasons and the limit ----------

    @GetMapping("/reasons")
    public List<ReasonView> reasons() {
        return reasons.list();
    }

    @PostMapping("/reasons")
    @Requires(Permission.INVENTORY_APPROVE)
    public ResponseEntity<ReasonView> createReason(@Valid @RequestBody ReasonService.ReasonRequest request) {
        ReasonView created = reasons.create(request);
        return ResponseEntity.created(URI.create("/api/inventory/reasons/" + created.id())).body(created);
    }

    @PutMapping("/reasons/{id}")
    @Requires(Permission.INVENTORY_APPROVE)
    public ReasonView updateReason(@PathVariable UUID id, @Valid @RequestBody ReasonService.ReasonRequest request) {
        return reasons.update(id, request);
    }

    @DeleteMapping("/reasons/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Requires(Permission.INVENTORY_APPROVE)
    public void deleteReason(@PathVariable UUID id) {
        reasons.delete(id);
    }

    @GetMapping("/settings")
    public SettingsView settings() {
        return reasons.settings();
    }

    @PutMapping("/settings")
    @Requires(Permission.INVENTORY_APPROVE)
    public SettingsView updateSettings(@Valid @RequestBody ReasonService.SettingsRequest request) {
        return reasons.update(request);
    }

    private static PageRequest page(int page, int size) {
        return PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE));
    }

    /** Newest first. */
    private static PageRequest page(int page, int size, String... by) {
        return page(page, size).withSort(Sort.by(Sort.Direction.DESC, by));
    }
}
