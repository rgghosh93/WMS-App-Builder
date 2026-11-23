package com.wms.putaway;

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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.wms.common.api.PageResponse;
import com.wms.putaway.PutawayViews.RuleView;
import com.wms.putaway.PutawayViews.TaskView;
import com.wms.users.Permission;
import com.wms.users.Requires;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/putaway")
public class PutawayController {

    private static final int MAX_PAGE_SIZE = 200;

    private final PutawayService putaway;
    private final PutawayRuleService rules;

    public PutawayController(PutawayService putaway, PutawayRuleService rules) {
        this.putaway = putaway;
        this.rules = rules;
    }

    /** Newest first. */
    @GetMapping("/tasks")
    public PageResponse<TaskView> tasks(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) PutawayTaskStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "createdAt", "taskNumber"));
        return PageResponse.of(putaway.search(new PutawaySearch(q, status), pageable), t -> t);
    }

    @GetMapping("/tasks/{id}")
    public TaskView task(@PathVariable UUID id) {
        return putaway.get(id);
    }

    /** In the order they are tried. */
    @GetMapping("/rules")
    public List<RuleView> rules() {
        return rules.list();
    }

    @GetMapping("/rules/{id}")
    public RuleView rule(@PathVariable UUID id) {
        return rules.get(id);
    }

    @PostMapping("/rules")
    @Requires(Permission.PUTAWAY_RULES)
    public ResponseEntity<RuleView> createRule(@Valid @RequestBody PutawayRuleRequest request) {
        RuleView created = rules.create(request);
        return ResponseEntity.created(URI.create("/api/putaway/rules/" + created.id())).body(created);
    }

    @PutMapping("/rules/{id}")
    @Requires(Permission.PUTAWAY_RULES)
    public RuleView updateRule(@PathVariable UUID id, @Valid @RequestBody PutawayRuleRequest request) {
        return rules.update(id, request);
    }

    @DeleteMapping("/rules/{id}")
    @Requires(Permission.PUTAWAY_RULES)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteRule(@PathVariable UUID id) {
        rules.delete(id);
    }
}
