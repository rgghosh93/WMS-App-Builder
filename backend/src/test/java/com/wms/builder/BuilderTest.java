package com.wms.builder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.wms.builder.llm.Llm;
import com.wms.builder.llm.LlmGateway;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The App Builder end to end, with a scripted stand-in for Claude and a small git repository of its own in place of the
 * WMS code: a change request is made, the agent reads and changes files, is refused what it may not touch, and its work
 * is committed to the change request's branch.
 */
@SpringBootTest(properties = {"wms.builder.enabled=true", "wms.builder.checks=false", "wms.builder.preview-ports=18090-18094"})
@AutoConfigureMockMvc
class BuilderTest {

    private static final Path HOME = temp();
    private static final Path SOURCE = HOME.resolve("source");
    /** What previews were sent to make their administrator. */
    private static final List<String> SETUPS = new java.util.concurrent.CopyOnWriteArrayList<>();

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private ScriptedLlm llm;
    @Autowired private PreviewManager previews;

    @TestConfiguration
    static class Script {
        @Bean
        @Primary
        ScriptedLlm scriptedLlm() {
            return new ScriptedLlm();
        }
    }

    @DynamicPropertySource
    static void repository(DynamicPropertyRegistry registry) throws Exception {
        Files.createDirectories(SOURCE.resolve("docs"));
        Files.writeString(SOURCE.resolve("docs/hello.txt"), "Hello\nfrom the WMS\n");
        git(SOURCE, "init", "--quiet", "--initial-branch=main");
        git(SOURCE, "-c", "user.name=t", "-c", "user.email=t@t", "add", "--all");
        Files.writeString(SOURCE.resolve("docs/doors.txt"), "Door 1\n");
        Files.writeString(SOURCE.resolve("docs/accept.txt"), "Hello\n");
        Files.writeString(SOURCE.resolve("docs/gates.txt"), "Door 1\n");
        git(SOURCE, "-c", "user.name=t", "-c", "user.email=t@t", "add", "--all");
        git(SOURCE, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "--quiet", "-m", "start");
        git(SOURCE, "branch", "builder");
        registry.add("wms.builder.source", SOURCE::toString);
        registry.add("wms.builder.workdir", () -> HOME.resolve("work").toString());
    }

    @BeforeEach
    void freshScript() {
        llm.reset();
        SETUPS.clear();
        previews.useLauncher(BuilderTest::healthOnly);
    }

    @Test
    void theAgentChangesTheCodeOnTheChangeRequestsOwnBranch() throws Exception {
        llm.uses("Let me look at the greeting.", ScriptedLlm.call("c1", "read_file", Map.of("path", "docs/hello.txt")));
        llm.uses(null,
                ScriptedLlm.call("c2", "edit_file", Map.of("path", "docs/hello.txt", "old_text", "Hello\n", "new_text", "Hello there\n")),
                ScriptedLlm.call("c3", "write_file", Map.of("path", ".github/workflows/x.yml", "content", "on: push")),
                ScriptedLlm.call("c4", "write_file", Map.of("path", "backend/src/main/resources/db/migration/V31__x.sql", "content", "")),
                ScriptedLlm.call("c5", "read_file", Map.of("path", "../outside.txt")));
        llm.says("The greeting now says hello there.");

        JsonNode created = json(mvc.perform(post("/api/builder/changes").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"Make the greeting friendlier\\nIt is too short.\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.change.title").value("Make the greeting friendlier"))
                .andExpect(jsonPath("$.change.status").value("DRAFTING"))
                .andReturn().getResponse().getContentAsString());
        String id = created.at("/change/id").asString();
        String number = created.at("/change/number").asString();

        JsonNode done = settled(id);
        assertThat(done.at("/lastProblem").isNull()).isTrue();
        List<String> roles = done.get("entries").valueStream().map(e -> e.get("role").asString()).toList();
        assertThat(roles).containsExactly("PERSON", "AGENT", "RESULTS", "AGENT", "RESULTS", "AGENT");

        JsonNode read = done.at("/entries/2/blocks/0");
        assertThat(read.get("type").asString()).isEqualTo("tool_result");
        assertThat(read.get("content").asString()).contains("1\tHello", "2\tfrom the WMS");

        JsonNode results = done.at("/entries/4/blocks");
        assertThat(results.get(0).get("error").asBoolean()).isFalse();
        assertThat(results.get(1).get("error").asBoolean()).isTrue();
        assertThat(results.get(1).get("content").asString()).contains("can't be changed by the App Builder");
        assertThat(results.get(2).get("content").asString()).contains("numbered from V1000__");
        assertThat(results.get(3).get("content").asString()).contains("outside the WMS code");

        assertThat(done.at("/files").valueStream().map(JsonNode::asString).toList()).containsExactly("M docs/hello.txt");
        assertThat(done.at("/inputTokens").asLong()).isEqualTo(300);
        assertThat(done.at("/outputTokens").asLong()).isEqualTo(30);

        Path work = HOME.resolve("work").resolve(number);
        assertThat(Files.readString(work.resolve("docs/hello.txt"))).isEqualTo("Hello there\nfrom the WMS\n");
        assertThat(git(work, "rev-parse", "--abbrev-ref", "HEAD").strip()).isEqualTo("change/" + number.toLowerCase());
        assertThat(git(work, "log", "-1", "--format=%s").strip()).isEqualTo(number + " Make the greeting friendlier");
        assertThat(Files.readString(SOURCE.resolve("docs/hello.txt"))).as("where it started from").isEqualTo("Hello\nfrom the WMS\n");

        // Every call had the same system prompt and tools; the last one carried the whole conversation.
        assertThat(llm.requests).hasSize(3);
        assertThat(llm.requests.stream().map(Llm.Request::system).distinct()).hasSize(1);
        assertThat(llm.requests.getLast().messages()).hasSize(5);

        // Saying more carries the same conversation on.
        llm.says("Done.");
        mvc.perform(post("/api/builder/changes/" + id + "/messages").contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"Thanks\"}")).andExpect(status().isAccepted());
        settled(id);
        List<Llm.Message> carried = llm.requests.getLast().messages();
        assertThat(carried).hasSize(7);
        assertThat(carried.getLast().blocks()).containsExactly(new Llm.Text("Thanks"));
        assertThat(carried.get(5).role()).isEqualTo(Llm.Role.ASSISTANT);

        mvc.perform(get("/api/builder/changes")).andExpect(jsonPath("$[?(@.number == '" + number + "')].working").value(false));
    }

    @Test
    void oneRunAtATimeAndItCanBeStopped() throws Exception {
        CountDownLatch thinking = new CountDownLatch(1);
        CountDownLatch carryOn = new CountDownLatch(1);
        llm.then(() -> {
            thinking.countDown();
            await(carryOn);
            return ScriptedLlm.reply(Llm.Stop.TOOL_USE, List.of(ScriptedLlm.call("c1", "list_files", Map.of())));
        });

        String id = json(mvc.perform(post("/api/builder/changes").contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"Add a field\"}")).andReturn().getResponse().getContentAsString()).at("/change/id").asString();
        assertThat(thinking.await(10, TimeUnit.SECONDS)).isTrue();

        mvc.perform(post("/api/builder/changes/" + id + "/messages").contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"Also another\"}")).andExpect(status().isConflict());
        mvc.perform(post("/api/builder/changes/" + id + "/stop")).andExpect(status().isOk());
        carryOn.countDown();

        JsonNode stopped = settled(id);
        assertThat(stopped.at("/lastProblem").asString()).startsWith("Stopped as asked");
        assertThat(stopped.get("entries").valueStream().map(e -> e.get("role").asString()).toList())
                .containsExactly("PERSON", "AGENT", "RESULTS");
        assertThat(llm.requests).hasSize(1);
    }

    @Test
    void whatTheModelSaysWentWrongIsShown() throws Exception {
        llm.then(() -> {
            throw new LlmGateway.LlmException("Claude is busy or the account's limit is reached. Try again in a minute.", null);
        });
        String id = json(mvc.perform(post("/api/builder/changes").contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"Add a report\"}")).andReturn().getResponse().getContentAsString()).at("/change/id").asString();
        assertThat(settled(id).at("/lastProblem").asString()).isEqualTo("Claude is busy or the account's limit is reached. Try again in a minute.");
    }

    @Test
    void somethingHasToBeSaid() throws Exception {
        mvc.perform(post("/api/builder/changes").contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"  \"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/builder/status")).andExpect(jsonPath("$.enabled").value(true));
        mvc.perform(get("/api/navigation/modules")).andExpect(jsonPath("$[?(@.id == 'administration')].pages[?(@.id == 'app-builder')]").exists());
    }

    @Test
    void anAcceptedChangeGoesOntoTheBuilderBranch() throws Exception {
        String id = changed("docs/accept.txt", "Hello\n", "Good morning\n");

        mvc.perform(post("/api/builder/changes/" + id + "/accept")).andExpect(status().isOk())
                .andExpect(jsonPath("$.change.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.accepted.by").value("TEST"))
                .andExpect(jsonPath("$.accepted.into").value("builder"))
                .andExpect(jsonPath("$.accepted.upToDate").value(true));

        assertThat(git(SOURCE, "show", "builder:docs/accept.txt")).isEqualTo("Good morning\n");
        assertThat(git(SOURCE, "log", "-1", "--format=%s", "builder")).startsWith("Accept CR-");
        assertThat(git(SOURCE, "show", "main:docs/accept.txt")).as("the base").isEqualTo("Hello\n");

        mvc.perform(post("/api/builder/changes/" + id + "/accept")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("This is already accepted, and nothing has changed in it since"));

        // More work makes it not accepted as it is now, until it is accepted again.
        llm.uses(null, ScriptedLlm.call("e2", "edit_file", Map.of("path", "docs/accept.txt", "old_text", "Good morning", "new_text", "Good day")));
        llm.says("Changed it again.");
        mvc.perform(post("/api/builder/changes/" + id + "/messages").contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"Say good day instead\"}")).andExpect(status().isAccepted());
        assertThat(settled(id).at("/accepted/upToDate").asBoolean()).isFalse();
        mvc.perform(post("/api/builder/changes/" + id + "/accept")).andExpect(jsonPath("$.accepted.upToDate").value(true));
        assertThat(git(SOURCE, "show", "builder:docs/accept.txt")).isEqualTo("Good day\n");
    }

    @Test
    void aChangeThatClashesIsNotAccepted() throws Exception {
        String id = changed("docs/doors.txt", "Door 1\n", "Dock door 1\n");
        // Meanwhile someone else's change to the same line was accepted.
        git(SOURCE, "checkout", "--quiet", "builder");
        Files.writeString(SOURCE.resolve("docs/doors.txt"), "Door one\n");
        git(SOURCE, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "--quiet", "-am", "someone else");
        git(SOURCE, "checkout", "--quiet", "main");
        String before = git(SOURCE, "rev-parse", "builder");

        mvc.perform(post("/api/builder/changes/" + id + "/accept")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.startsWith("This change clashes with changes already accepted")))
                .andExpect(jsonPath("$.errors.files").value("docs/doors.txt"));
        assertThat(git(SOURCE, "rev-parse", "builder")).isEqualTo(before);
        mvc.perform(get("/api/builder/changes/" + id)).andExpect(jsonPath("$.change.status").value("DRAFTING"))
                .andExpect(jsonPath("$.change.working").value(false));
    }

    @Test
    void nothingChangedIsNothingToAccept() throws Exception {
        llm.says("Which owners should this apply to?");
        String id = json(mvc.perform(post("/api/builder/changes").contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"Add a field\"}")).andReturn().getResponse().getContentAsString()).at("/change/id").asString();
        settled(id);
        mvc.perform(post("/api/builder/changes/" + id + "/accept")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("There is nothing to accept yet: the agent hasn't changed anything"));
    }

    @Test
    void aPreviewRunsTheChangeUntilTheAgentWorksOnItAgain() throws Exception {
        String id = changed("docs/hello.txt", "Hello\n", "Hi\n");

        mvc.perform(post("/api/builder/changes/" + id + "/preview")).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.preview.state").exists());
        JsonNode running = null;
        Instant until = Instant.now().plus(Duration.ofSeconds(20));
        while (Instant.now().isBefore(until)) {
            running = json(mvc.perform(get("/api/builder/changes/" + id)).andReturn().getResponse().getContentAsString()).get("preview");
            if ("RUNNING".equals(running.get("state").asString())) {
                break;
            }
            Thread.sleep(100);
        }
        assertThat(running.get("state").asString()).isEqualTo("RUNNING");
        assertThat(running.get("url").asString()).matches("http://127\\.0\\.0\\.1:1809[0-4]");
        assertThat(running.get("upToDate").asBoolean()).isTrue();
        // You are its administrator, with a password made up for it.
        assertThat(running.at("/signIn/userCode").asString()).isEqualTo("TEST");
        String password = running.at("/signIn/password").asString();
        assertThat(password).matches("[a-z2-9]{4}(-[a-z2-9]{4}){3}");
        JsonNode setUp = mapper.readTree(SETUPS.getFirst());
        assertThat(setUp.get("userCode").asString()).isEqualTo("TEST");
        assertThat(setUp.get("displayName").asString()).isEqualTo("Test Admin");
        assertThat(setUp.get("password").asString()).isEqualTo(password);

        llm.says("Nothing to change.");
        mvc.perform(post("/api/builder/changes/" + id + "/messages").contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"Anything else?\"}")).andExpect(jsonPath("$.preview").isEmpty());

        settled(id);
        mvc.perform(post("/api/builder/changes/" + id + "/preview")).andExpect(status().isAccepted());
        mvc.perform(delete("/api/builder/changes/" + id + "/preview")).andExpect(status().isOk()).andExpect(jsonPath("$.preview").isEmpty());
    }

    @Test
    void theAgentAsksQuestionsAndWaitsForTheAnswers() throws Exception {
        llm.uses("Before I build this, a few questions.",
                ScriptedLlm.call("q1", "ask_questions", Map.of("intro", "Two things decide how staging works.", "questions", List.of(
                        Map.of("question", "How should stock get into staging?", "multiple", true, "recommended", "Inventory Move",
                                "options", List.of(Map.of("label", "Inventory Move"), Map.of("label", "Put-away", "description", "As a stop on the way"))),
                        Map.of("question", "Anything else?")))),
                ScriptedLlm.call("l1", "list_files", Map.of()));

        String id = json(mvc.perform(post("/api/builder/changes").contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"Add staging\"}")).andReturn().getResponse().getContentAsString()).at("/change/id").asString();
        JsonNode waiting = settled(id);
        assertThat(waiting.at("/lastProblem").isNull()).isTrue();
        assertThat(waiting.at("/questions/callId").asString()).isEqualTo("q1");
        assertThat(waiting.at("/questions/intro").asString()).isEqualTo("Two things decide how staging works.");
        assertThat(waiting.at("/questions/questions/0/multiple").asBoolean()).isTrue();
        assertThat(waiting.at("/questions/questions/0/recommended").asString()).isEqualTo("Inventory Move");
        assertThat(waiting.at("/questions/questions/0/options/1/description").asString()).isEqualTo("As a stop on the way");
        assertThat(waiting.at("/questions/questions/1/options").size()).isZero();
        // What was asked for alongside the questions wasn't done.
        assertThat(waiting.at("/entries/2/blocks/0/callId").asString()).isEqualTo("l1");
        assertThat(waiting.at("/entries/2/blocks/0/error").asBoolean()).isTrue();

        mvc.perform(post("/api/builder/changes/" + id + "/answers").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"callId\":\"q1\",\"answers\":[{\"question\":0,\"chosen\":[\"Inventory Move\",\"Teleport\"]}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.q0").value("isn't one of the choices"))
                .andExpect(jsonPath("$.errors.q1").value("answer this"));

        llm.says("Thanks, building it now.");
        mvc.perform(post("/api/builder/changes/" + id + "/answers").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"callId\":\"q1\",\"answers\":[{\"question\":0,\"chosen\":[\"Inventory Move\",\"Put-away\"]},"
                                + "{\"question\":1,\"chosen\":[],\"other\":\"Count it too\"}]}"))
                .andExpect(status().isAccepted());
        JsonNode answered = settled(id);
        assertThat(answered.at("/questions").isNull()).isTrue();

        Llm.Message sent = llm.requests.getLast().messages().getLast();
        assertThat(sent.role()).isEqualTo(Llm.Role.USER);
        assertThat(sent.blocks()).containsExactly(
                new Llm.ToolResult("l1", "Not done: ask_questions is used on its own. Do this again after the answers if it is still needed.", true),
                new Llm.ToolResult("q1", "1. How should stock get into staging?\nInventory Move, Put-away\n\n2. Anything else?\nCount it too", false));

        mvc.perform(post("/api/builder/changes/" + id + "/answers").contentType(MediaType.APPLICATION_JSON)
                .content("{\"callId\":\"q1\",\"answers\":[]}")).andExpect(status().isConflict());
    }

    @Test
    void answeringInOwnWordsAnswersTheQuestionsToo() throws Exception {
        llm.uses(null, ScriptedLlm.call("q2", "ask_questions", Map.of("questions", List.of(Map.of("question", "Which owners?")))));
        String id = json(mvc.perform(post("/api/builder/changes").contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"Add a field\"}")).andReturn().getResponse().getContentAsString()).at("/change/id").asString();
        assertThat(settled(id).at("/questions/callId").asString()).isEqualTo("q2");

        llm.says("Got it.");
        mvc.perform(post("/api/builder/changes/" + id + "/messages").contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"Only ACME\"}")).andExpect(status().isAccepted());
        assertThat(settled(id).at("/questions").isNull()).isTrue();
        assertThat(llm.requests.getLast().messages().getLast().blocks()).containsExactly(
                new Llm.ToolResult("q2", "They answered in their own words instead; see their message.", false), new Llm.Text("Only ACME"));
    }

    @Test
    void tooManyQuestionsAreSentBack() throws Exception {
        Map<String, Object> one = Map.of("question", "Why?");
        llm.uses(null, ScriptedLlm.call("q3", "ask_questions", Map.of("questions", List.of(one, one, one, one, one))));
        llm.says("I'll ask fewer.");
        String id = json(mvc.perform(post("/api/builder/changes").contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"Add a field\"}")).andReturn().getResponse().getContentAsString()).at("/change/id").asString();
        JsonNode done = settled(id);
        assertThat(done.at("/questions").isNull()).isTrue();
        assertThat(done.at("/entries/2/blocks/0/content").asString()).startsWith("Ask at most 4 questions");
    }

    @Test
    void aClosedChangeRequestIsLeftAloneUntilItIsReopened() throws Exception {
        String id = changed("docs/hello.txt", "Hello\n", "Hiya\n");
        String number = json(mvc.perform(get("/api/builder/changes/" + id)).andReturn().getResponse().getContentAsString())
                .at("/change/number").asString();
        Path work = HOME.resolve("work").resolve(number);
        Files.createDirectories(work.resolve("backend/target/classes"));
        Files.writeString(work.resolve("backend/target/classes/built.txt"), "built");
        Files.createDirectories(work.resolve("frontend/node_modules/x"));

        mvc.perform(post("/api/builder/changes/" + id + "/discard").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Not needed after all\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.change.status").value("DISCARDED"))
                .andExpect(jsonPath("$.discarded.by").value("TEST"))
                .andExpect(jsonPath("$.discarded.reason").value("Not needed after all"));

        // What was built is cleared away; the change itself stays.
        Instant until = Instant.now().plus(Duration.ofSeconds(10));
        while (Files.exists(work.resolve("backend/target")) && Instant.now().isBefore(until)) {
            Thread.sleep(50);
        }
        assertThat(work.resolve("backend/target")).doesNotExist();
        assertThat(work.resolve("frontend/node_modules")).doesNotExist();
        assertThat(Files.readString(work.resolve("docs/hello.txt"))).isEqualTo("Hiya\nfrom the WMS\n");

        for (String refused : List.of("messages", "accept", "preview")) {
            mvc.perform(post("/api/builder/changes/" + id + "/" + refused).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"text\":\"More\",\"callId\":\"x\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.detail").value("This change request is closed. Reopen it to carry on."));
        }
        mvc.perform(post("/api/builder/changes/" + id + "/discard")).andExpect(status().isConflict());

        mvc.perform(post("/api/builder/changes/" + id + "/reopen")).andExpect(status().isOk())
                .andExpect(jsonPath("$.change.status").value("DRAFTING"))
                .andExpect(jsonPath("$.discarded").isEmpty())
                .andExpect(jsonPath("$.files[0]").value("M docs/hello.txt"));
        mvc.perform(post("/api/builder/changes/" + id + "/reopen")).andExpect(status().isConflict());

        llm.says("Back on it.");
        mvc.perform(post("/api/builder/changes/" + id + "/messages").contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"Carry on\"}")).andExpect(status().isAccepted());
        assertThat(settled(id).at("/lastProblem").isNull()).isTrue();
    }

    @Test
    void closingStopsTheAgentAfterTheStepItIsOn() throws Exception {
        CountDownLatch thinking = new CountDownLatch(1);
        CountDownLatch carryOn = new CountDownLatch(1);
        llm.then(() -> {
            thinking.countDown();
            await(carryOn);
            return ScriptedLlm.reply(Llm.Stop.TOOL_USE, List.of(ScriptedLlm.call("c1", "list_files", Map.of())));
        });
        String id = json(mvc.perform(post("/api/builder/changes").contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"Add a field\"}")).andReturn().getResponse().getContentAsString()).at("/change/id").asString();
        assertThat(thinking.await(10, TimeUnit.SECONDS)).isTrue();

        mvc.perform(post("/api/builder/changes/" + id + "/discard")).andExpect(status().isOk())
                .andExpect(jsonPath("$.change.status").value("DISCARDED"));
        carryOn.countDown();

        JsonNode closed = settled(id);
        assertThat(closed.at("/change/status").asString()).isEqualTo("DISCARDED");
        assertThat(closed.at("/lastProblem").isNull()).as("closing isn't a problem").isTrue();
        assertThat(llm.requests).hasSize(1);
    }

    @Test
    void theAgentFinishesWithASummaryThatEndsItsTurn() throws Exception {
        Map<String, Object> summary = Map.of(
                "headline", "Staging is a new location type, ready to try.",
                "changes", List.of(Map.of("area", "Locations page", "what", "Staging is in the type list.")),
                "try_it", List.of("Create a staging location", "Move an LPN into it"),
                "checklist", List.of(Map.of("item", "Staging can be created", "result", "passed"),
                        Map.of("item", "Moved on a real device", "result", "not_checked", "note", "No device here")),
                "left_out", List.of("Counting staging locations"));
        llm.uses("Here is what I built.", ScriptedLlm.call("r1", "report_change", summary));

        String id = json(mvc.perform(post("/api/builder/changes").contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"Add staging\"}")).andReturn().getResponse().getContentAsString()).at("/change/id").asString();
        JsonNode done = settled(id);
        assertThat(done.at("/lastProblem").isNull()).isTrue();
        assertThat(llm.requests).as("nothing more is asked of the model").hasSize(1);
        assertThat(done.at("/entries/1/blocks/1/name").asString()).isEqualTo("report_change");
        assertThat(done.at("/entries/1/blocks/1/input/checklist/1/result").asString()).isEqualTo("not_checked");
        assertThat(done.at("/entries/2/blocks/0/content").asString()).isEqualTo("The summary is shown to them.");

        // Carrying on afterwards is a normal conversation.
        llm.says("Sure.");
        mvc.perform(post("/api/builder/changes/" + id + "/messages").contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"Thanks\"}")).andExpect(status().isAccepted());
        settled(id);
        assertThat(llm.requests.getLast().messages().getLast().blocks()).containsExactly(
                new Llm.ToolResult("r1", "The summary is shown to them.", false), new Llm.Text("Thanks"));
    }

    @Test
    void anIncompleteSummaryIsSentBack() throws Exception {
        llm.uses(null, ScriptedLlm.call("r2", "report_change", Map.of("headline", "Done",
                "changes", List.of(Map.of("area", "Orders", "what", "A field")),
                "checklist", List.of(Map.of("item", "It works", "result", "probably")))));
        llm.says("Fixed.");
        String id = json(mvc.perform(post("/api/builder/changes").contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"Add a field\"}")).andReturn().getResponse().getContentAsString()).at("/change/id").asString();
        JsonNode done = settled(id);
        assertThat(done.at("/entries/2/blocks/0/error").asBoolean()).isTrue();
        assertThat(done.at("/entries/2/blocks/0/content").asString()).startsWith("Each checklist item needs");
        assertThat(llm.requests).hasSize(2);
    }

    @Test
    void whoeverAskedIsToldWhenTheAgentFinishesOrNeedsThem() throws Exception {
        llm.uses(null, ScriptedLlm.call("q9", "ask_questions", Map.of("questions", List.of(Map.of("question", "Which owners?")))));
        String id = json(mvc.perform(post("/api/builder/changes").contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"Notify me\"}")).andReturn().getResponse().getContentAsString()).at("/change/id").asString();
        settled(id);
        JsonNode asking = mine(id);
        assertThat(asking.get("kind").asString()).isEqualTo("QUESTIONS");
        assertThat(asking.get("message").asString()).isEqualTo("The agent has questions for you before it builds anything.");

        // Answering starts the agent again, and what it needed is done.
        llm.says("Building it.");
        mvc.perform(post("/api/builder/changes/" + id + "/messages").contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"All of them\"}")).andExpect(status().isAccepted());
        settled(id);
        assertThat(mine(id).get("kind").asString()).isEqualTo("FINISHED");

        mvc.perform(post("/api/builder/changes/" + id + "/seen")).andExpect(status().isNoContent());
        assertThat(mine(id)).isNull();

        llm.then(() -> {
            throw new LlmGateway.LlmException("Can't reach Claude. Check the connection and try again.", null);
        });
        mvc.perform(post("/api/builder/changes/" + id + "/messages").contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"One more thing\"}")).andExpect(status().isAccepted());
        settled(id);
        JsonNode problem = mine(id);
        assertThat(problem.get("kind").asString()).isEqualTo("PROBLEM");
        assertThat(problem.get("message").asString()).isEqualTo("Can't reach Claude. Check the connection and try again.");

        // A closed request needs nobody.
        mvc.perform(post("/api/builder/changes/" + id + "/discard")).andExpect(status().isOk());
        assertThat(mine(id)).isNull();
    }

    @Test
    void onlyDevelopersSeeHowTheChangeIsBuilt() throws Exception {
        llm.uses("Let me look at the greeting.", ScriptedLlm.call("d1", "read_file",
                Map.of("path", "docs/gates.txt", "purpose", "Checking what dock doors are called today")));
        llm.uses(null, ScriptedLlm.call("d2", "edit_file", Map.of("path", "docs/gates.txt", "old_text", "Door 1", "new_text", "Gate 1")));
        llm.uses(null, ScriptedLlm.call("d3", "report_change", Map.of("headline", "Doors are gates now.",
                        "changes", List.of(Map.of("area", "Dock doors", "what", "They are called gates.")),
                        "checklist", List.of(Map.of("item", "Gates show", "result", "passed")))));
        String id = json(mvc.perform(post("/api/builder/changes").contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"Call doors gates\"}")).andReturn().getResponse().getContentAsString()).at("/change/id").asString();
        settled(id);

        com.wms.users.DesktopUser analyst = new com.wms.users.DesktopUser(java.util.UUID.randomUUID(), "ANNA", "Anna Analyst",
                java.util.Set.of(com.wms.users.Permission.DESKTOP, com.wms.users.Permission.APP_BUILDER), false);
        JsonNode plain = json(mvc.perform(get("/api/builder/changes/" + id).requestAttr(com.wms.users.DesktopUser.ATTRIBUTE, analyst))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(plain.get("developer").asBoolean()).isFalse();
        assertThat(plain.get("files").size()).isZero();
        assertThat(plain.get("branch").isNull()).isTrue();
        assertThat(plain.get("inputTokens").asLong()).isZero();
        assertThat(plain.at("/entries/1/blocks/0/text").asString()).isEqualTo("Let me look at the greeting.");
        assertThat(plain.at("/entries/1/blocks/1/name").asString()).isEqualTo("read_file");
        assertThat(plain.at("/entries/1/blocks/1/input").size()).as("only what it was for").isEqualTo(1);
        // A step without a purpose says which part of the WMS it was in, never which file.
        assertThat(plain.at("/entries/3/blocks/0/input/purpose").isMissingNode()).isTrue();
        assertThat(plain.at("/entries/1/blocks/1/input/purpose").asString()).isEqualTo("Checking what dock doors are called today");
        assertThat(plain.at("/entries/2/blocks/0/content").asString()).as("what was in it").isEmpty();
        assertThat(plain.at("/entries/5/blocks/0/input/headline").asString()).as("the summary").isEqualTo("Doors are gates now.");
        assertThat(plain.toString()).doesNotContain("docs/gates.txt").doesNotContain("Door 1");

        JsonNode developer = json(mvc.perform(get("/api/builder/changes/" + id)).andReturn().getResponse().getContentAsString());
        assertThat(developer.get("developer").asBoolean()).isTrue();
        assertThat(developer.at("/files/0").asString()).isEqualTo("M docs/gates.txt");
        assertThat(developer.at("/entries/1/blocks/1/input/path").asString()).isEqualTo("docs/gates.txt");
    }

    // --- Helpers ---------------------------------------------------------------------------------------------------

    /** What the change request needs from the person signed in, if anything. */
    private JsonNode mine(String id) throws Exception {
        JsonNode all = json(mvc.perform(get("/api/builder/notifications")).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString());
        return all.valueStream().filter(n -> n.get("id").asString().equals(id)).findFirst().orElse(null);
    }

    /** A change request in which the agent has replaced {@code from} with {@code to} in {@code path}. */
    private String changed(String path, String from, String to) throws Exception {
        llm.uses(null, ScriptedLlm.call("e1", "edit_file", Map.of("path", path, "old_text", from, "new_text", to)));
        llm.says("Done.");
        String id = json(mvc.perform(post("/api/builder/changes").contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"Change " + path + "\"}")).andReturn().getResponse().getContentAsString()).at("/change/id").asString();
        assertThat(settled(id).at("/files").valueStream().map(JsonNode::asString).toList()).containsExactly("M " + path);
        return id;
    }

    /** Stands in for a preview server: answers the health check on its port, and nothing else. */
    private static PreviewManager.Handle healthOnly(Workspace workspace, int port, Path log) throws IOException {
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), port), 0);
        server.createContext("/api/health", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.createContext("/api/session/setup", exchange -> {
            SETUPS.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(SETUPS.size() > 1 ? 409 : 200, -1);
            exchange.close();
        });
        server.start();
        return new PreviewManager.Handle() {
            private volatile boolean alive = true;

            @Override
            public boolean isAlive() {
                return alive;
            }

            @Override
            public void stop() {
                alive = false;
                server.stop(0);
            }
        };
    }

    /** The change request once the agent has stopped working on it. */
    private JsonNode settled(String id) throws Exception {
        Instant until = Instant.now().plus(Duration.ofSeconds(20));
        while (Instant.now().isBefore(until)) {
            JsonNode detail = json(mvc.perform(get("/api/builder/changes/" + id)).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString());
            if (!detail.at("/change/working").asBoolean()) {
                return detail;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("The agent was still working after 20 seconds");
    }

    private JsonNode json(String body) {
        return mapper.readTree(body);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Path temp() {
        try {
            return Files.createTempDirectory("builder-test");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static String git(Path in, String... args) throws Exception {
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        Process process = new ProcessBuilder(command).directory(in.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + ": " + output);
        }
        return output;
    }
}
