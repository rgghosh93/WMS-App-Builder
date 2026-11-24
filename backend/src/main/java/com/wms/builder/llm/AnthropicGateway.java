package com.wms.builder.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.CredentialResolutionException;
import com.anthropic.errors.NoCredentialsException;
import com.anthropic.errors.OverloadedException;
import com.anthropic.errors.PermissionDeniedException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlockParam;

/**
 * Claude, through Anthropic's own library. Thinking is adaptive and stays on; the model's reasoning goes back to it
 * unchanged within a run. The system prompt and tools never change within a conversation, so the start of it is cached.
 */
public class AnthropicGateway implements LlmGateway {

    private final AnthropicClient client;
    private final String model;
    private final OutputConfig.Effort effort;
    private final boolean fallbacks;

    /**
     * @param fallbacks when Claude declines, let Anthropic answer with another suitable model instead (server-side
     *                  fallback, a beta)
     */
    public AnthropicGateway(AnthropicClient client, String model, String effort, boolean fallbacks) {
        this.client = client;
        this.model = model;
        this.effort = OutputConfig.Effort.of(effort);
        this.fallbacks = fallbacks;
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public Llm.Reply send(Llm.Request request) {
        Message answer;
        try {
            answer = client.messages().create(params(request));
        } catch (NoCredentialsException | CredentialResolutionException e) {
            throw new LlmException("Claude isn't set up here: there is no API key on the server.", e);
        } catch (UnauthorizedException | PermissionDeniedException e) {
            throw new LlmException("Claude refused the builder's API key. Check that it is right and still active.", e);
        } catch (RateLimitException | OverloadedException e) {
            throw new LlmException("Claude is busy or the account's limit is reached. Try again in a minute.", e);
        } catch (AnthropicServiceException e) {
            throw new LlmException("Claude couldn't answer (HTTP " + e.statusCode() + "): " + e.getMessage(), e);
        } catch (AnthropicIoException e) {
            throw new LlmException("Can't reach Claude. Check the connection and try again.", e);
        } catch (AnthropicException e) {
            throw new LlmException("Claude couldn't answer: " + e.getMessage(), e);
        }
        return reply(answer);
    }

    MessageCreateParams params(Llm.Request request) {
        MessageCreateParams.Builder params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(request.maxTokens())
                .system(request.system())
                .outputConfig(OutputConfig.builder().effort(effort).build())
                // Caches the conversation up to its latest turn, so each step pays only for what is new.
                .cacheControl(CacheControlEphemeral.builder().build());
        for (Llm.Tool tool : request.tools()) {
            params.addTool(tool(tool));
        }
        for (Llm.Message message : request.messages()) {
            params.addMessage(message(message));
        }
        if (fallbacks) {
            params.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01");
            params.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        }
        return params.build();
    }

    private static Tool tool(Llm.Tool tool) {
        Map<String, Object> schema = tool.inputSchema();
        Tool.InputSchema.Builder input = Tool.InputSchema.builder();
        if (schema.get("properties") instanceof Map<?, ?> properties) {
            Tool.InputSchema.Properties.Builder each = Tool.InputSchema.Properties.builder();
            properties.forEach((name, property) -> each.putAdditionalProperty((String) name, JsonValue.from(property)));
            input.properties(each.build());
        }
        if (schema.get("required") instanceof List<?> required) {
            input.required(required.stream().map(String::valueOf).toList());
        }
        return Tool.builder().name(tool.name()).description(tool.description()).inputSchema(input.build()).build();
    }

    private static MessageParam message(Llm.Message message) {
        if (message.providerForm() instanceof MessageParam same) {
            return same;
        }
        List<ContentBlockParam> blocks = new ArrayList<>();
        for (Llm.Block block : message.blocks()) {
            blocks.add(switch (block) {
                case Llm.Text text -> ContentBlockParam.ofText(TextBlockParam.builder().text(text.text()).build());
                case Llm.ToolCall call -> ContentBlockParam.ofToolUse(ToolUseBlockParam.builder().id(call.id()).name(call.name())
                        .input(ToolUseBlockParam.Input.builder().putAllAdditionalProperties(json(call.input())).build()).build());
                case Llm.ToolResult result -> ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                        .toolUseId(result.callId()).content(result.content()).isError(result.error()).build());
            });
        }
        return message.role() == Llm.Role.USER ? MessageParam.ofUserOfContentBlockParams(blocks)
                : MessageParam.ofAssistantOfContentBlockParams(blocks);
    }

    private static Map<String, JsonValue> json(Map<String, Object> values) {
        Map<String, JsonValue> json = new java.util.LinkedHashMap<>();
        values.forEach((key, value) -> json.put(key, JsonValue.from(value)));
        return json;
    }

    @SuppressWarnings("unchecked")
    static Llm.Reply reply(Message answer) {
        List<Llm.Block> blocks = new ArrayList<>();
        for (ContentBlock block : answer.content()) {
            block.text().ifPresent(text -> blocks.add(new Llm.Text(text.text())));
            block.toolUse().ifPresent(use -> blocks.add(new Llm.ToolCall(use.id(), use.name(), use._input().convert(Map.class))));
        }
        StopReason reason = answer.stopReason().orElse(null);
        Llm.Stop stop = reason == null ? Llm.Stop.OTHER
                : reason.equals(StopReason.END_TURN) || reason.equals(StopReason.STOP_SEQUENCE) ? Llm.Stop.DONE
                : reason.equals(StopReason.TOOL_USE) ? Llm.Stop.TOOL_USE
                : reason.equals(StopReason.MAX_TOKENS) ? Llm.Stop.MAX_TOKENS
                : reason.equals(StopReason.REFUSAL) ? Llm.Stop.REFUSED
                : Llm.Stop.OTHER;
        String note = stop == Llm.Stop.REFUSED
                ? answer.stopDetails().flatMap(details -> details.explanation()).orElse("Claude declined to continue.")
                : stop == Llm.Stop.OTHER && reason != null ? "Claude stopped: " + reason.asString() : null;
        Llm.Message message = new Llm.Message(Llm.Role.ASSISTANT, blocks, answer.toParam());
        return new Llm.Reply(message, stop, new Llm.Usage(answer.usage().inputTokens()
                + answer.usage().cacheReadInputTokens().orElse(0L) + answer.usage().cacheCreationInputTokens().orElse(0L),
                answer.usage().outputTokens()), answer.model().asString(), note);
    }
}
