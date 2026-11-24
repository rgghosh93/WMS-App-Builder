package com.wms.builder;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.wms.builder.llm.Llm;

/** A kept conversation is made one the model accepts, whatever interrupted it. */
class ConversationTest {

    private static final Llm.ToolCall READ = new Llm.ToolCall("c1", "read_file", Map.of("path", "a"));
    private static final Llm.ToolCall LIST = new Llm.ToolCall("c2", "list_files", Map.of());

    @Test
    void anUnbrokenConversationIsKeptAsItWas() {
        List<Llm.Message> kept = List.of(Llm.Message.userText("Add a field"), Llm.Message.assistant(List.of(READ)),
                Llm.Message.user(List.of(new Llm.ToolResult("c1", "text", false))), Llm.Message.assistant(List.of(new Llm.Text("Done"))),
                Llm.Message.userText("Thanks"));
        assertThat(BuilderService.conversation(kept)).isEqualTo(kept);
    }

    @Test
    void whatThePersonSaysAfterToolResultsJoinsThem() {
        List<Llm.Message> kept = List.of(Llm.Message.userText("Add a field"), Llm.Message.assistant(List.of(READ)),
                Llm.Message.user(List.of(new Llm.ToolResult("c1", "text", false))), Llm.Message.userText("Carry on"));
        List<Llm.Message> sent = BuilderService.conversation(kept);
        assertThat(sent).hasSize(3);
        assertThat(sent.get(2).blocks()).containsExactly(new Llm.ToolResult("c1", "text", false), new Llm.Text("Carry on"));
    }

    @Test
    void toolCallsNeverAnsweredAreAnsweredAsNotDone() {
        List<Llm.Message> kept = List.of(Llm.Message.userText("Add a field"), Llm.Message.assistant(List.of(READ, LIST)),
                Llm.Message.user(List.of(new Llm.ToolResult("c2", "files", false))), Llm.Message.assistant(List.of(READ)),
                Llm.Message.userText("Are you there?"));
        List<Llm.Message> sent = BuilderService.conversation(kept);
        assertThat(sent).hasSize(5);
        assertThat(sent.get(2).blocks()).containsExactly(new Llm.ToolResult("c1", "Not done: the work was interrupted.", true),
                new Llm.ToolResult("c2", "files", false));
        assertThat(sent.get(4).blocks()).containsExactly(new Llm.ToolResult("c1", "Not done: the work was interrupted.", true),
                new Llm.Text("Are you there?"));
    }

    @Test
    void titlesAreTheFirstLineKeptShort() {
        assertThat(BuilderService.title("  Add a temperature field\nwhen receiving chilled items")).isEqualTo("Add a temperature field");
        assertThat(BuilderService.title("x".repeat(100))).hasSize(78).endsWith("…");
    }
}
