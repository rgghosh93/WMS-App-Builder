package com.wms.builder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wms.builder.llm.Llm;
import com.wms.common.api.ApiException;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Change requests and their conversations: starting them, carrying them on, and showing where they are. */
@Service
public class BuilderService {

    static final int SHOWN_TEXT = 4000;
    private static final TypeReference<List<Llm.Block>> BLOCKS = new TypeReference<>() { };

    private final ChangeRequestRepository changes;
    private final ChangeEntryRepository entries;
    private final BuilderSettings settings;
    private final ObjectMapper mapper;
    private final PreviewManager previews;

    public BuilderService(ChangeRequestRepository changes, ChangeEntryRepository entries, BuilderSettings settings,
                          ObjectMapper mapper, PreviewManager previews) {
        this.changes = changes;
        this.entries = entries;
        this.settings = settings;
        this.mapper = mapper;
        this.previews = previews;
    }

    /**
     * @param previews     change requests can be tried in a preview here
     * @param acceptBranch the branch accepted changes go to
     */
    public record Status(boolean enabled, String model, boolean checks, boolean previews, String acceptBranch) {
    }

    public record Summary(UUID id, String number, String title, ChangeRequest.Status status, boolean working,
                          String requestedBy, Instant createdAt, Instant updatedAt) {
    }

    /**
     * @param files       what the change touches so far, as "M path" (changed), "A path" (added) or "D path" (deleted)
     * @param lastProblem why the agent's last run didn't finish, if it didn't
     */
    public record Detail(Summary change, List<EntryView> entries, List<String> files, String branch, String lastProblem,
                         long inputTokens, long outputTokens, Accepted accepted, PreviewManager.View preview, Pending questions,
                         Discarded discarded, boolean developer) {
    }

    /** Who closed it, when and why. */
    public record Discarded(Instant at, String by, String reason) {
    }

    /** Questions the agent asked that haven't been answered; the agent waits for them. */
    public record Pending(String callId, String intro, List<BuilderTools.Questions.Question> questions) {
    }

    /**
     * An answer to one of the agent's questions.
     *
     * @param question which, counting from 0
     * @param chosen   the choices picked
     * @param other    an answer in their own words, as well or instead
     */
    public record Answer(int question, List<String> chosen, String other) {
    }

    /**
     * When the change was last accepted, and by whom.
     *
     * @param upToDate nothing has changed in it since
     */
    public record Accepted(Instant at, String by, String into, boolean upToDate) {
    }

    /** A turn of the conversation as shown; long texts are cut short. */
    public record EntryView(int seq, ChangeEntry.Role role, List<Llm.Block> blocks, String model, Instant at) {
    }

    public Status status() {
        return new Status(settings.enabled(), settings.model(), settings.checks(), settings.previews(), settings.acceptBranch());
    }

    @Transactional(readOnly = true)
    public List<Summary> list() {
        requireEnabled();
        return changes.findAllByOrderByUpdatedAtDesc().stream().map(BuilderService::summary).toList();
    }

    /**
     * The change request as someone sees it. Only developers see how it is being built: the code read and changed, test
     * output, where its work is kept and what it has used. Everyone else sees the conversation in business terms (AB-30).
     */
    @Transactional(readOnly = true)
    public Detail detail(UUID id, boolean developer) {
        requireEnabled();
        ChangeRequest change = find(id);
        List<ChangeEntry> kept = entries.findByChangeIdOrderBySeq(id);
        java.util.Map<String, String> tools = new java.util.HashMap<>();
        kept.forEach(e -> blocks(e).stream().filter(Llm.ToolCall.class::isInstance).map(Llm.ToolCall.class::cast)
                .forEach(call -> tools.put(call.id(), call.name())));
        List<EntryView> shown = kept.stream().map(e -> new EntryView(e.getSeq(), e.getRole(),
                developer ? shown(blocks(e)) : plainly(blocks(e), tools), developer ? e.getModel() : null, e.getCreatedAt())).toList();
        List<String> files = List.of();
        String head = null;
        try {
            Workspace workspace = existingWorkspace(change);
            if (workspace != null) {
                files = workspace.changedFiles();
                head = workspace.head();
            }
        } catch (RuntimeException e) {
            // Shown without them: its copy may still be being made, or the agent may be in the middle of something.
        }
        Accepted accepted = change.getAcceptedAt() == null ? null : new Accepted(change.getAcceptedAt(), change.getAcceptedBy(),
                settings.acceptBranch(), change.getAcceptedCommit() != null && change.getAcceptedCommit().equals(head));
        PreviewManager.View preview = previews.view(id, head);
        if (!developer && preview != null && preview.problem() != null) {
            preview = new PreviewManager.View(preview.state(), preview.url(), null, preview.upToDate(), preview.step(),
                    "A developer can see what went wrong.", preview.since(), preview.signIn());
        }
        String problem = change.getLastProblem();
        if (!developer && problem != null && problem.startsWith("Something went wrong")) {
            problem = "Something went wrong. Send a message to try again; a developer can see what happened.";
        }
        return new Detail(summary(change), shown, developer ? files : List.of(), developer ? change.getBranch() : null, problem,
                developer ? change.getInputTokens() : 0, developer ? change.getOutputTokens() : 0, accepted, preview,
                change.isWorking() || closed(change) ? null : pending(kept),
                closed(change) ? new Discarded(change.getDiscardedAt(), change.getDiscardedBy(), change.getDiscardReason()) : null,
                developer);
    }

    /** A new change request, with what the person asked for. The agent is to start on it. */
    @Transactional
    public UUID create(String text, String userCode) {
        requireEnabled();
        Instant now = Instant.now();
        ChangeRequest change = changes.save(new ChangeRequest(changes.nextNumber(), title(text), userCode, now));
        add(change.getId(), ChangeEntry.Role.PERSON, List.of(new Llm.Text(text.strip())), null, null, null, now);
        changes.flush();
        start(change.getId(), now);
        return change.getId();
    }

    /** The person says more; the agent is to carry on. Said while it is waiting for answers, it is the answer. */
    @Transactional
    public void say(UUID id, String text) {
        requireEnabled();
        requireOpen(find(id));
        Pending waiting = pending(entries.findByChangeIdOrderBySeq(id));
        Instant now = Instant.now();
        start(id, now);
        List<Llm.Block> said = waiting == null ? List.of(new Llm.Text(text.strip()))
                : List.of(new Llm.ToolResult(waiting.callId(), "They answered in their own words instead; see their message.", false),
                        new Llm.Text(text.strip()));
        add(id, ChangeEntry.Role.PERSON, said, null, null, null, now);
    }

    /** The person answers the agent's questions; the agent is to carry on with the answers. */
    @Transactional
    public void answer(UUID id, String callId, List<Answer> answers) {
        requireEnabled();
        requireOpen(find(id));
        Pending waiting = pending(entries.findByChangeIdOrderBySeq(id));
        if (waiting == null || !waiting.callId().equals(callId)) {
            throw ApiException.conflict("Those questions have been answered already", java.util.Map.of());
        }
        java.util.Map<String, String> errors = new java.util.LinkedHashMap<>();
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < waiting.questions().size(); i++) {
            BuilderTools.Questions.Question question = waiting.questions().get(i);
            int at = i;
            Answer answer = answers == null ? null : answers.stream().filter(a -> a.question() == at).findFirst().orElse(null);
            List<String> chosen = answer == null || answer.chosen() == null ? List.of()
                    : answer.chosen().stream().map(String::strip).filter(c -> !c.isEmpty()).distinct().toList();
            String other = answer == null || answer.other() == null ? "" : answer.other().strip();
            if (chosen.stream().anyMatch(c -> question.options().stream().noneMatch(o -> o.label().equals(c)))) {
                errors.put("q" + i, "isn't one of the choices");
            } else if (chosen.size() > 1 && !question.multiple()) {
                errors.put("q" + i, "pick one");
            } else if (chosen.isEmpty() && other.isEmpty()) {
                errors.put("q" + i, "answer this");
            } else if (other.length() > 2000) {
                errors.put("q" + i, "is too long");
            }
            List<String> said = new ArrayList<>(chosen);
            if (!other.isEmpty()) {
                said.add(chosen.isEmpty() ? other : "Also: " + other);
            }
            text.append(i + 1).append(". ").append(question.question()).append("\n").append(String.join(", ", said)).append("\n\n");
        }
        if (!errors.isEmpty()) {
            throw ApiException.invalid("Answer every question", errors);
        }
        Instant now = Instant.now();
        start(id, now);
        add(id, ChangeEntry.Role.PERSON, List.of(new Llm.ToolResult(callId, text.toString().strip(), false)), null, null, null, now);
    }

    /** The last questions the agent asked, if nothing has answered them yet. */
    private Pending pending(List<ChangeEntry> kept) {
        Llm.ToolCall asked = null;
        Set<String> answered = new HashSet<>();
        for (ChangeEntry entry : kept) {
            for (Llm.Block block : blocks(entry)) {
                if (block instanceof Llm.ToolCall call && call.name().equals(BuilderTools.ASK)
                        && BuilderTools.Questions.problem(call.input()) == null) {
                    asked = call;
                } else if (block instanceof Llm.ToolResult result) {
                    answered.add(result.callId());
                }
            }
        }
        if (asked == null || answered.contains(asked.id())) {
            return null;
        }
        BuilderTools.Questions questions = BuilderTools.Questions.of(asked.input());
        return new Pending(asked.id(), questions.intro(), questions.questions());
    }

    /**
     * Closes the change request: nothing more is done on it until it is reopened.
     *
     * @return the agent was working on it, and stops after the step it is on
     */
    @Transactional
    public boolean discard(UUID id, String by, String reason) {
        requireEnabled();
        ChangeRequest change = find(id);
        String why = reason == null || reason.isBlank() ? null : reason.strip();
        if (changes.discard(id, by, why, Instant.now()) == 0) {
            throw ApiException.conflict("This change request is closed already", java.util.Map.of());
        }
        return change.isWorking();
    }

    /** Opens a closed change request again, as it was. */
    @Transactional
    public void reopen(UUID id) {
        requireEnabled();
        ChangeRequest change = find(id);
        ChangeRequest.Status was = change.getAcceptedAt() == null ? ChangeRequest.Status.DRAFTING : ChangeRequest.Status.ACCEPTED;
        if (changes.reopen(id, was, Instant.now()) == 0) {
            throw ApiException.conflict("This change request isn't closed", java.util.Map.of());
        }
    }

    static boolean closed(ChangeRequest change) {
        return change.getStatus() == ChangeRequest.Status.DISCARDED;
    }

    static void requireOpen(ChangeRequest change) {
        if (closed(change)) {
            throw ApiException.conflict("This change request is closed. Reopen it to carry on.", java.util.Map.of());
        }
    }

    /** Something a change request needs from the person who asked for it. */
    public record Notification(UUID id, String number, String title, ChangeRequest.Attention kind, String message, Instant at) {
    }

    /** What the change requests someone asked for need from them, latest first. */
    @Transactional(readOnly = true)
    public List<Notification> notifications(String userCode) {
        requireEnabled();
        return changes.findByRequestedByAndAttentionIsNotNullOrderByAttentionAtDesc(userCode).stream()
                .filter(c -> !closed(c))
                .map(c -> new Notification(c.getId(), c.getNumber(), c.getTitle(), c.getAttention(), switch (c.getAttention()) {
                    case FINISHED -> "The agent has finished. Have a look at what it did.";
                    case QUESTIONS -> "The agent has questions for you before it builds anything.";
                    case PROBLEM -> c.getLastProblem() == null ? "The agent stopped without finishing." : c.getLastProblem();
                }, c.getAttentionAt())).toList();
    }

    /** The person who asked for it has looked at it; it doesn't need them any more. Others looking changes nothing. */
    @Transactional
    public void seen(UUID id, String userCode) {
        requireEnabled();
        ChangeRequest change = find(id);
        if (change.getRequestedBy().equals(userCode) && change.getAttention() != null && !change.isWorking()) {
            changes.needsAttention(id, null, null);
        }
    }

    @Transactional
    void needsAttention(UUID id, ChangeRequest.Attention attention) {
        changes.needsAttention(id, attention, Instant.now());
    }

    @Transactional
    public void stop(UUID id) {
        requireEnabled();
        find(id);
        changes.requestStop(id);
    }

    // --- For the agent's runs ---------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    ChangeRequest change(UUID id) {
        return find(id);
    }

    /** The conversation so far, ready to be carried on. */
    @Transactional(readOnly = true)
    List<Llm.Message> conversation(UUID id) {
        return conversation(entries.findByChangeIdOrderBySeq(id).stream()
                .map(e -> new Llm.Message(e.getRole() == ChangeEntry.Role.AGENT ? Llm.Role.ASSISTANT : Llm.Role.USER, blocks(e), null))
                .toList());
    }

    /**
     * Makes a kept conversation one the model accepts: tool calls that were never answered (the server stopped part way)
     * are answered as not done, and what the person said after tool results goes in the same message as them.
     */
    static List<Llm.Message> conversation(List<Llm.Message> kept) {
        // What the person said and what tools gave, one after another, are one message: tool results, then words.
        List<Llm.Message> joined = new ArrayList<>();
        for (Llm.Message message : kept) {
            if (!joined.isEmpty() && joined.getLast().role() == Llm.Role.USER && message.role() == Llm.Role.USER) {
                List<Llm.Block> merged = new ArrayList<>(joined.getLast().blocks());
                merged.addAll(message.blocks());
                joined.set(joined.size() - 1, Llm.Message.user(merged));
            } else if (!message.blocks().isEmpty()) {
                joined.add(message);
            }
        }
        List<Llm.Message> out = new ArrayList<>();
        for (Llm.Message message : joined) {
            if (!out.isEmpty() && calls(out.getLast())) {
                if (message.role() == Llm.Role.USER) {
                    message = answered(out.getLast(), message);
                } else {
                    out.add(answered(out.getLast(), Llm.Message.user(List.of())));
                }
            }
            out.add(message);
        }
        if (!out.isEmpty() && calls(out.getLast())) {
            out.add(answered(out.getLast(), Llm.Message.user(List.of())));
        }
        return out;
    }

    private static boolean calls(Llm.Message message) {
        return message.role() == Llm.Role.ASSISTANT && message.blocks().stream().anyMatch(Llm.ToolCall.class::isInstance);
    }

    /** {@code next}, with a "not done" result first for each of {@code call}'s tool calls it doesn't answer. */
    private static Llm.Message answered(Llm.Message call, Llm.Message next) {
        Set<String> answered = new HashSet<>();
        next.blocks().stream().filter(Llm.ToolResult.class::isInstance).forEach(b -> answered.add(((Llm.ToolResult) b).callId()));
        List<Llm.Block> blocks = new ArrayList<>();
        call.blocks().stream().filter(Llm.ToolCall.class::isInstance).map(Llm.ToolCall.class::cast)
                .filter(c -> !answered.contains(c.id()))
                .forEach(c -> blocks.add(new Llm.ToolResult(c.id(), "Not done: the work was interrupted.", true)));
        blocks.addAll(next.blocks());
        return Llm.Message.user(blocks);
    }

    @Transactional
    void agentSaid(UUID id, Llm.Reply reply) {
        Instant now = Instant.now();
        add(id, ChangeEntry.Role.AGENT, reply.message().blocks(), reply.model(), reply.usage().inputTokens(),
                reply.usage().outputTokens(), now);
        changes.addUsage(id, reply.usage().inputTokens(), reply.usage().outputTokens(), now);
    }

    @Transactional
    void toolsAnswered(UUID id, Llm.Message results) {
        add(id, ChangeEntry.Role.RESULTS, results.blocks(), null, null, null, Instant.now());
    }

    @Transactional(readOnly = true)
    boolean stopRequested(UUID id) {
        return changes.findById(id).map(ChangeRequest::isStopRequested).orElse(true);
    }

    @Transactional
    void finished(UUID id, String problem) {
        changes.stopWorking(id, problem == null ? null : problem.substring(0, Math.min(problem.length(), 1000)), Instant.now());
    }

    /** Marks it as busy for accepting, so the agent isn't started on it meanwhile. */
    @Transactional
    ChangeRequest beginAccepting(UUID id) {
        requireEnabled();
        ChangeRequest change = find(id);
        requireOpen(change);
        start(id, Instant.now());
        return change;
    }

    @Transactional
    void accepted(UUID id, String by, String commit) {
        changes.accepted(id, by, commit, Instant.now());
    }

    @Transactional
    void restarted() {
        changes.stopAllWork("The server restarted while the agent was working. Send a message to let it carry on.", Instant.now());
    }

    // --- Helpers ---------------------------------------------------------------------------------------------------

    private void start(UUID id, Instant now) {
        if (changes.startWorking(id, now) == 0) {
            throw ApiException.conflict("The agent is still working on this. Wait for it, or stop it first.", java.util.Map.of());
        }
    }

    private void add(UUID id, ChangeEntry.Role role, List<Llm.Block> blocks, String model, Long input, Long output, Instant now) {
        entries.save(new ChangeEntry(id, entries.lastSeq(id) + 1, role, mapper.writerFor(BLOCKS).writeValueAsString(blocks), model,
                input, output, now));
    }

    private List<Llm.Block> blocks(ChangeEntry entry) {
        return mapper.readValue(entry.getBlocks(), BLOCKS);
    }

    /** The change request's checkout, if it has one yet. */
    Workspace existingWorkspace(ChangeRequest change) {
        java.nio.file.Path dir = settings.workdir().resolve(change.getNumber());
        if (!java.nio.file.Files.isDirectory(dir.resolve(".git"))) {
            return null;
        }
        return Workspace.open(dir, settings.source(), settings.baseBranch(), change.getBranch());
    }

    /**
     * A turn as people who aren't developers see it: what the agent said, its questions and summary, and their answers,
     * but only what kind of work it did, not on which code. Test results are just whether they passed.
     */
    private static List<Llm.Block> plainly(List<Llm.Block> blocks, java.util.Map<String, String> tools) {
        java.util.Set<String> business = java.util.Set.of(BuilderTools.ASK, BuilderTools.REPORT);
        return blocks.stream().map(block -> switch (block) {
            case Llm.Text text -> text;
            case Llm.ToolCall call when business.contains(call.name()) -> call;
            case Llm.ToolCall call -> {
                // What kind of step and what it says it is for, never what it worked on.
                java.util.Map<String, Object> kept = new java.util.LinkedHashMap<>();
                if (call.input().get(BuilderTools.PURPOSE) instanceof String purpose) {
                    kept.put(BuilderTools.PURPOSE, purpose);
                }
                String area = WmsAreas.of(call.input().get("path"));
                if (area != null) {
                    kept.put("area", area);
                }
                if (BuilderTools.CHECKS.equals(call.name()) && call.input().get("which") instanceof String which) {
                    kept.put("which", which);
                }
                yield (Llm.Block) new Llm.ToolCall(call.id(), call.name(), kept);
            }
            case Llm.ToolResult result when tools.get(result.callId()) == null || business.contains(tools.get(result.callId())) -> result;
            case Llm.ToolResult result when BuilderTools.CHECKS.equals(tools.get(result.callId())) ->
                    new Llm.ToolResult(result.callId(), result.content().startsWith("Passed") ? "passed" : "not passed", result.error());
            case Llm.ToolResult result -> new Llm.ToolResult(result.callId(), "", result.error());
        }).toList();
    }

    private static List<Llm.Block> shown(List<Llm.Block> blocks) {
        return blocks.stream().map(block -> switch (block) {
            case Llm.Text text -> text;
            case Llm.ToolResult result -> new Llm.ToolResult(result.callId(), cut(result.content()), result.error());
            case Llm.ToolCall call -> {
                java.util.Map<String, Object> input = new java.util.LinkedHashMap<>();
                call.input().forEach((key, value) -> input.put(key, value instanceof String s ? cut(s) : value));
                String area = WmsAreas.of(call.input().get("path"));
                if (area != null) {
                    input.putIfAbsent("area", area);
                }
                yield (Llm.Block) new Llm.ToolCall(call.id(), call.name(), input);
            }
        }).toList();
    }

    private static String cut(String text) {
        return text == null || text.length() <= SHOWN_TEXT ? text : text.substring(0, SHOWN_TEXT) + "\n… (" + (text.length() - SHOWN_TEXT)
                + " more characters)";
    }

    /** The first line of what was asked, kept short. */
    static String title(String text) {
        String first = text.strip().lines().findFirst().orElse("").strip();
        return first.length() <= 80 ? first : first.substring(0, 77).strip() + "…";
    }

    private ChangeRequest find(UUID id) {
        return changes.findById(id).orElseThrow(() -> ApiException.notFound("There is no change request " + id));
    }

    void requireEnabled() {
        if (!settings.enabled()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "The App Builder is switched off here");
        }
    }

    private static Summary summary(ChangeRequest c) {
        return new Summary(c.getId(), c.getNumber(), c.getTitle(), c.getStatus(), c.isWorking(), c.getRequestedBy(), c.getCreatedAt(),
                c.getUpdatedAt());
    }
}
