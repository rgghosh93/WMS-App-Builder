package com.wms.work;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.wms.common.api.PageResponse;
import com.wms.users.Permission;
import com.wms.users.Requires;
import com.wms.work.WorkViews.KindSummary;
import com.wms.work.WorkViews.Setting;
import com.wms.work.WorkViews.TaskRow;
import com.wms.work.WorkViews.Worker;

@RestController
@RequestMapping("/api/tasks")
public class WorkController {

    private static final int MAX_PAGE_SIZE = 200;

    private final WorkService work;

    public WorkController(WorkService work) {
        this.work = work;
    }

    /** @param changed how many tasks */
    public record Changed(int changed) {
    }

    /** Most urgent first. */
    @GetMapping
    public PageResponse<TaskRow> queue(@RequestParam(required = false) String q, @RequestParam(required = false) WorkKind kind,
                                       @RequestParam(required = false) WorkService.State state,
                                       @RequestParam(required = false) UUID person,
                                       @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return PageResponse.of(work.queue(new WorkService.Filter(q, kind, state, person),
                PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE))), r -> r);
    }

    @GetMapping("/summary")
    public List<KindSummary> summary() {
        return work.summary();
    }

    @GetMapping("/people")
    public List<Worker> people() {
        return work.people();
    }

    @PostMapping("/priority")
    @Requires(Permission.TASKS)
    public Changed prioritize(@RequestBody WorkService.Prioritize request) {
        return new Changed(work.prioritize(request));
    }

    @PostMapping("/assign")
    @Requires(Permission.TASKS)
    public Changed assign(@RequestBody WorkService.Assign request) {
        return new Changed(work.assign(request));
    }

    @GetMapping("/settings")
    public List<Setting> settings() {
        return work.settings();
    }

    @PutMapping("/settings")
    @Requires(Permission.TASKS)
    public List<Setting> change(@RequestBody List<WorkService.ChangeSetting> changes) {
        return work.change(changes);
    }
}
