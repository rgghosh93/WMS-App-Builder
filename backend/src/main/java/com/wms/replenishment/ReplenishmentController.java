package com.wms.replenishment;

import java.net.URI;
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
import com.wms.replenishment.ReplenishmentRuleService.RuleRequest;
import com.wms.replenishment.ReplenishmentViews.RuleView;
import com.wms.replenishment.ReplenishmentViews.TaskView;
import com.wms.users.DesktopUser;
import com.wms.users.Permission;
import com.wms.users.Requires;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/replenishment")
public class ReplenishmentController {

    private static final int MAX_PAGE_SIZE = 200;

    private final ReplenishmentService replenishment;
    private final ReplenishmentRuleService rules;
    private final ReplenishmentRun run;

    public ReplenishmentController(ReplenishmentService replenishment, ReplenishmentRuleService rules, ReplenishmentRun run) {
        this.replenishment = replenishment;
        this.rules = rules;
        this.run = run;
    }

    /** Newest first. */
    @GetMapping("/tasks")
    public PageResponse<TaskView> tasks(@RequestParam(required = false) String q,
                                        @RequestParam(required = false) ReplenishmentTask.Status status,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "25") int size) {
        return PageResponse.of(replenishment.search(q, status, PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "createdAt", "taskNumber"))), t -> t);
    }

    @PostMapping("/tasks/{id}/cancel")
    @Requires(Permission.REPLENISHMENT)
    public TaskView cancel(@RequestAttribute(DesktopUser.ATTRIBUTE) DesktopUser user, @PathVariable UUID id) {
        return replenishment.cancel(user.displayName(), id);
    }

    /** Works out now what every rule needs. */
    @PostMapping("/run")
    @Requires(Permission.REPLENISHMENT)
    public ReplenishmentRun.Result run() {
        return run.all();
    }

    @GetMapping("/rules")
    public List<RuleView> rules() {
        return rules.list();
    }

    @GetMapping("/rules/{id}")
    public RuleView rule(@PathVariable UUID id) {
        return rules.get(id);
    }

    @PostMapping("/rules")
    @Requires(Permission.REPLENISHMENT)
    public ResponseEntity<RuleView> create(@Valid @RequestBody RuleRequest request) {
        RuleView created = rules.create(request);
        return ResponseEntity.created(URI.create("/api/replenishment/rules/" + created.id())).body(created);
    }

    @PutMapping("/rules/{id}")
    @Requires(Permission.REPLENISHMENT)
    public RuleView update(@PathVariable UUID id, @Valid @RequestBody RuleRequest request) {
        return rules.update(id, request);
    }

    @DeleteMapping("/rules/{id}")
    @Requires(Permission.REPLENISHMENT)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        rules.delete(id);
    }
}
