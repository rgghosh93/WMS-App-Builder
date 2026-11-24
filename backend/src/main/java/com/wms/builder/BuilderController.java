package com.wms.builder;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.wms.users.DesktopUser;
import com.wms.users.Permission;
import com.wms.users.Requires;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The App Builder: change requests, the conversation with the agent about each, and what it has changed. */
@RestController
@RequestMapping("/api/builder")
@Requires(Permission.APP_BUILDER)
public class BuilderController {

    private final BuilderService service;
    private final BuilderRunner runner;

    public BuilderController(BuilderService service, BuilderRunner runner) {
        this.service = service;
        this.runner = runner;
    }

    /** What someone says to the agent. */
    public record Say(@NotBlank(message = "Say what you'd like") @Size(max = 20_000, message = "is too long") String text) {
    }

    @GetMapping("/status")
    public BuilderService.Status status() {
        return service.status();
    }

    /** What the change requests the person signed in asked for need from them. */
    @GetMapping("/notifications")
    public List<BuilderService.Notification> notifications(@RequestAttribute(DesktopUser.ATTRIBUTE) DesktopUser user) {
        return service.notifications(user.userCode());
    }

    @PostMapping("/changes/{id}/seen")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void seen(@PathVariable UUID id, @RequestAttribute(DesktopUser.ATTRIBUTE) DesktopUser user) {
        service.seen(id, user.userCode());
    }

    @GetMapping("/changes")
    public List<BuilderService.Summary> list() {
        return service.list();
    }

    @PostMapping("/changes")
    public ResponseEntity<BuilderService.Detail> create(@Valid @RequestBody Say say,
                                                        @RequestAttribute(DesktopUser.ATTRIBUTE) DesktopUser user) {
        UUID id = service.create(say.text(), user.userCode());
        runner.begin(id);
        return ResponseEntity.created(URI.create("/api/builder/changes/" + id)).body(service.detail(id, developer(user)));
    }

    @GetMapping("/changes/{id}")
    public BuilderService.Detail detail(@PathVariable UUID id, @RequestAttribute(DesktopUser.ATTRIBUTE) DesktopUser user) {
        return service.detail(id, developer(user));
    }

    @PostMapping("/changes/{id}/messages")
    public ResponseEntity<BuilderService.Detail> say(@PathVariable UUID id, @Valid @RequestBody Say say,
                                                     @RequestAttribute(DesktopUser.ATTRIBUTE) DesktopUser user) {
        service.say(id, say.text());
        runner.begin(id);
        return ResponseEntity.accepted().body(service.detail(id, developer(user)));
    }

    /** Answers to the questions the agent asked. */
    public record Answers(@NotBlank String callId, List<BuilderService.Answer> answers) {
    }

    @PostMapping("/changes/{id}/answers")
    public ResponseEntity<BuilderService.Detail> answer(@PathVariable UUID id, @Valid @RequestBody Answers answers,
                                                        @RequestAttribute(DesktopUser.ATTRIBUTE) DesktopUser user) {
        service.answer(id, answers.callId(), answers.answers());
        runner.begin(id);
        return ResponseEntity.accepted().body(service.detail(id, developer(user)));
    }

    @PostMapping("/changes/{id}/accept")
    public BuilderService.Detail accept(@PathVariable UUID id, @RequestAttribute(DesktopUser.ATTRIBUTE) DesktopUser user) {
        runner.accept(id, user.userCode());
        return service.detail(id, developer(user));
    }

    @PostMapping("/changes/{id}/preview")
    public ResponseEntity<BuilderService.Detail> preview(@PathVariable UUID id,
                                                         @RequestAttribute(DesktopUser.ATTRIBUTE) DesktopUser user) {
        runner.preview(id, new PreviewManager.Person(user.userCode(), user.displayName()));
        return ResponseEntity.accepted().body(service.detail(id, developer(user)));
    }

    @DeleteMapping("/changes/{id}/preview")
    public BuilderService.Detail stopPreview(@PathVariable UUID id, @RequestAttribute(DesktopUser.ATTRIBUTE) DesktopUser user) {
        runner.stopPreview(id);
        return service.detail(id, developer(user));
    }

    /** Why a change request is being closed, if they say. */
    public record Discard(@Size(max = 500, message = "is too long") String reason) {
    }

    @PostMapping("/changes/{id}/discard")
    public BuilderService.Detail discard(@PathVariable UUID id, @Valid @RequestBody(required = false) Discard discard,
                                         @RequestAttribute(DesktopUser.ATTRIBUTE) DesktopUser user) {
        runner.discard(id, user.userCode(), discard == null ? null : discard.reason());
        return service.detail(id, developer(user));
    }

    @PostMapping("/changes/{id}/reopen")
    public BuilderService.Detail reopen(@PathVariable UUID id, @RequestAttribute(DesktopUser.ATTRIBUTE) DesktopUser user) {
        runner.reopen(id);
        return service.detail(id, developer(user));
    }

    @PostMapping("/changes/{id}/stop")
    public BuilderService.Detail stop(@PathVariable UUID id, @RequestAttribute(DesktopUser.ATTRIBUTE) DesktopUser user) {
        service.stop(id);
        return service.detail(id, developer(user));
    }

    /** They may see how the App Builder works, not only what it does. */
    private static boolean developer(DesktopUser user) {
        return user.permissions().contains(Permission.APP_BUILDER_DEVELOPER);
    }
}
