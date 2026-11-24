package com.wms.builder.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;

/** What the builder's conversation looks like to Claude. Nothing is sent. */
class AnthropicGatewayTest {

    private final AnthropicGateway gateway = new AnthropicGateway(AnthropicOkHttpClient.builder().apiKey("not-used").build(),
            "claude-opus-5-5", "high", true);

    @Test
    void theConversationToolsAndSettingsGoAcross() {
        Llm.Tool read = new Llm.Tool("read_file", "Read a file", Map.of("type", "object",
                "properties", Map.of("path", Map.of("type", "string")), "required", List.of("path")));
        Llm.Request request = new Llm.Request("You build changes.", List.of(read), List.of(
                Llm.Message.userText("Add a field"),
                Llm.Message.assistant(List.of(new Llm.Text("Looking"), new Llm.ToolCall("c1", "read_file", Map.of("path", "a.txt")))),
                Llm.Message.user(List.of(new Llm.ToolResult("c1", "1\tA", false), new Llm.Text("And quickly")))), 32_000);

        MessageCreateParams params = gateway.params(request);

        assertThat(params.model().asString()).isEqualTo("claude-opus-5-5");
        assertThat(params.maxTokens()).isEqualTo(32_000);
        assertThat(params.outputConfig().flatMap(OutputConfig::effort)).contains(OutputConfig.Effort.HIGH);
        assertThat(params.system().orElseThrow().asString()).isEqualTo("You build changes.");
        assertThat(params.tools().orElseThrow()).singleElement()
                .satisfies(t -> assertThat(t.asTool().name()).isEqualTo("read_file"));
        assertThat(params.messages()).hasSize(3);
        var call = params.messages().get(1).asAssistant().content().asContentBlockParams().get(1).asToolUse();
        assertThat(call.id()).isEqualTo("c1");
        assertThat(call.input()._additionalProperties()).containsEntry("path", JsonValue.from("a.txt"));
        var result = params.messages().get(2).asUser().content().asContentBlockParams().getFirst().asToolResult();
        assertThat(result.toolUseId()).isEqualTo("c1");
        assertThat(params._additionalBodyProperties()).containsEntry("fallbacks", JsonValue.from("default"));
        assertThat(params._additionalHeaders().values("anthropic-beta")).contains("server-side-fallback-2026-07-01");
    }
}
