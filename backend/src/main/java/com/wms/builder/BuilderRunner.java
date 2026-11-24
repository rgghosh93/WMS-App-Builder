package com.wms.builder;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.wms.builder.llm.Llm;
import com.wms.common.api.ApiException;
import com.wms.builder.llm.LlmGateway;

import jakarta.annotation.PreDestroy;

/**
 * Runs the agent on change requests, away from the requests that asked for it: each run carries one change request's
 * conversation on until the agent has answered, then commits what it changed to that change request's branch.
 */
@Component
public class BuilderRunner {

    private static final Logger log = LoggerFactory.getLogger(BuilderRunner.class);

    private final BuilderService service;
    private final BuilderSettings settings;
    private final ObjectProvider<LlmGateway> gateway;
    private final PreviewManager previews;
    private final ExecutorService runs = Executors.newFixedThreadPool(2, Thread.ofPlatform().name("app-builder-", 1).daemon().factory());

    public BuilderRunner(BuilderService service, BuilderSettings settings, ObjectProvider<LlmGateway> gateway, PreviewManager previews) {
        this.service = service;
        this.settings = settings;
        this.gateway = gateway;
        this.previews = previews;
    }

    /** Nothing was being worked on before the server started, whatever was kept. */
    @EventListener(ApplicationReadyEvent.class)
    void clearUp() {
        service.restarted();
    }

    @PreDestroy
    void shutDown() {
        runs.shutdownNow();
    }

    /** Starts the agent on a change request the service has marked as being worked on. */
    public void begin(UUID id) {
        // The agent changes and builds the same copy a preview runs from, so its preview stops; it would be out of date anyway.
        previews.stop(id);
        runs.submit(() -> work(id));
    }

    /** Builds and starts the change request's own copy of the WMS, to try it. */
    public void preview(UUID id, PreviewManager.Person person) {
        service.requireEnabled();
        ChangeRequest change = service.change(id);
        BuilderService.requireOpen(change);
        if (change.isWorking()) {
            throw ApiException.conflict("Wait for the agent to finish before previewing", Map.of());
        }
        Workspace workspace = service.existingWorkspace(change);
        if (workspace == null) {
            throw ApiException.conflict("There is nothing to preview yet", Map.of());
        }
        previews.start(id, change.getNumber(), workspace, person);
    }

    /**
     * Closes the change request. Its preview stops, and what was built to test and preview it is cleared away to free the
     * space; its code stays, so it can be reopened as it was.
     */
    public void discard(UUID id, String by, String reason) {
        boolean working = service.discard(id, by, reason);
        previews.stop(id);
        if (!working) {
            // The agent's run clears up after itself when it stops.
            runs.submit(() -> clearBuilds(id));
        }
    }

    public void reopen(UUID id) {
        service.reopen(id);
    }

    private void clearBuilds(UUID id) {
        try {
            Workspace workspace = service.existingWorkspace(service.change(id));
            if (workspace != null) {
                workspace.clearBuilds();
            }
        } catch (RuntimeException e) {
            log.warn("Couldn't clear the builds of closed change request {}", id, e);
        }
    }

    public void stopPreview(UUID id) {
        service.requireEnabled();
        service.change(id);
        previews.stop(id);
    }

    /**
     * Accepts the change: merges it into the branch the environment for trying changes runs, and pushes it there. Nothing
     * is merged if it clashes with what is there already.
     */
    public void accept(UUID id, String by) {
        ChangeRequest change = service.beginAccepting(id);
        String into = settings.acceptBranch();
        try {
            Workspace workspace = service.existingWorkspace(change);
            if (workspace == null || workspace.changedFiles().isEmpty()) {
                throw ApiException.conflict("There is nothing to accept yet: the agent hasn't changed anything", Map.of());
            }
            workspace.commit(change.getNumber() + " " + change.getTitle());
            String head = workspace.head();
            if (head.equals(change.getAcceptedCommit())) {
                throw ApiException.conflict("This is already accepted, and nothing has changed in it since", Map.of());
            }
            workspace.accept(into, "Accept " + change.getNumber() + ": " + change.getTitle() + "\n\nAccepted by " + by + ".");
            service.accepted(id, by, head);
        } catch (Workspace.Clash e) {
            throw ApiException.conflict("This change clashes with changes already accepted into " + into + ", so it wasn't accepted "
                    + "and " + into + " is as it was. Combining them isn't something the App Builder can do yet.",
                    Map.of("files", String.join(", ", e.files())));
        } catch (Workspace.Refused e) {
            throw ApiException.conflict(e.getMessage(), Map.of());
        } finally {
            service.finished(id, null);
        }
    }

    void work(UUID id) {
        String problem = null;
        ChangeRequest.Attention attention = ChangeRequest.Attention.PROBLEM;
        try {
            LlmGateway llm = gateway.getIfAvailable();
            if (llm == null) {
                problem = "There is no language model set up for the App Builder here.";
                return;
            }
            ChangeRequest change = service.change(id);
            List<Llm.Message> conversation = service.conversation(id);
            Workspace workspace = Workspace.open(settings.workdir().resolve(change.getNumber()), settings.source(),
                    settings.baseBranch(), change.getBranch());
            BuilderAgent agent = new BuilderAgent(llm, new BuilderTools(settings.checks()), settings.maxSteps());
            BuilderAgent.Outcome outcome;
            try {
                outcome = agent.run(conversation, workspace, new BuilderAgent.Listener() {
                    @Override
                    public void agent(Llm.Reply reply) {
                        service.agentSaid(id, reply);
                    }

                    @Override
                    public void results(Llm.Message results) {
                        service.toolsAnswered(id, results);
                    }
                }, () -> service.stopRequested(id));
            } finally {
                // Whatever got done is kept, even if the run went wrong part way.
                workspace.commit(change.getNumber() + " " + change.getTitle());
            }
            attention = switch (outcome) {
                case ANSWERED -> ChangeRequest.Attention.FINISHED;
                case ASKING -> ChangeRequest.Attention.QUESTIONS;
                // They stopped it themselves; they know.
                case STOPPED -> null;
                default -> ChangeRequest.Attention.PROBLEM;
            };
            problem = switch (outcome) {
                case ANSWERED, ASKING -> null;
                case STOPPED -> "Stopped as asked. Send a message to carry on.";
                case OUT_OF_STEPS -> "The agent took " + settings.maxSteps() + " steps without finishing. Send a message to let it carry on.";
                case REFUSED -> "Claude declined to carry on with this request.";
                case CUT_SHORT -> "The agent's answer was cut short. Send a message to let it carry on.";
            };
        } catch (LlmGateway.LlmException e) {
            log.warn("App Builder run for {} stopped: {}", id, e.getMessage());
            problem = e.getMessage();
        } catch (RuntimeException e) {
            log.error("App Builder run for {} failed", id, e);
            problem = "Something went wrong: " + e.getMessage();
        } finally {
            boolean closed = BuilderService.closed(service.change(id));
            service.finished(id, closed ? null : problem);
            if (closed) {
                clearBuilds(id);
            } else if (attention != null) {
                service.needsAttention(id, attention);
            }
        }
    }
}
