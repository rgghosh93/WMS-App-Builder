package com.wms.builder.llm;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * What the builder says to a language model and hears back, the same whichever provider answers (AR-5). Only a
 * {@link LlmGateway} knows a provider's own shapes.
 */
public final class Llm {

    private Llm() {
    }

    /** One call: the frozen system prompt and tools, and the whole conversation so far. */
    public record Request(String system, List<Tool> tools, List<Message> messages, int maxTokens) {
        public Request {
            tools = List.copyOf(tools);
            messages = List.copyOf(messages);
        }
    }

    /** Something the model may ask the builder to do. {@code inputSchema} is a JSON Schema object. */
    public record Tool(String name, String description, Map<String, Object> inputSchema) {
    }

    /** Who a message is from, as the model sees it. */
    public enum Role { USER, ASSISTANT }

    /**
     * One message of the conversation.
     *
     * @param providerForm the provider's own form of an assistant message heard in this run, which may carry more than
     *                     the blocks (such as the model's reasoning); sent back as it came while the run lasts, never stored
     */
    public record Message(Role role, List<Block> blocks, @JsonIgnore Object providerForm) {
        public Message {
            blocks = List.copyOf(blocks);
        }

        public static Message user(List<Block> blocks) {
            return new Message(Role.USER, blocks, null);
        }

        public static Message userText(String text) {
            return user(List.of(new Text(text)));
        }

        public static Message assistant(List<Block> blocks) {
            return new Message(Role.ASSISTANT, blocks, null);
        }
    }

    /** Part of a message. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = Text.class, name = "text"),
            @JsonSubTypes.Type(value = ToolCall.class, name = "tool_call"),
            @JsonSubTypes.Type(value = ToolResult.class, name = "tool_result")})
    public sealed interface Block permits Text, ToolCall, ToolResult {
    }

    public record Text(String text) implements Block {
    }

    /** The model asks for a tool to be used. */
    public record ToolCall(String id, String name, Map<String, Object> input) implements Block {
        public ToolCall {
            input = input == null ? Map.of() : input;
        }
    }

    /** What came of a tool call. */
    public record ToolResult(String callId, String content, boolean error) implements Block {
    }

    /** Why the model stopped. */
    public enum Stop {
        /** It has said what it has to say. */
        DONE,
        /** It is waiting for tool results. */
        TOOL_USE,
        /** It ran out of room for its answer. */
        MAX_TOKENS,
        /** It declined. */
        REFUSED,
        OTHER
    }

    public record Usage(long inputTokens, long outputTokens) {
        public static final Usage NONE = new Usage(0, 0);
    }

    /**
     * The model's answer.
     *
     * @param message the answer as the next assistant message, ready to be sent back
     * @param note    for a refusal or other unusual stop, what the provider said about it
     */
    public record Reply(Message message, Stop stop, Usage usage, String model, String note) {

        public List<ToolCall> toolCalls() {
            return message.blocks().stream().filter(ToolCall.class::isInstance).map(ToolCall.class::cast).toList();
        }

        public String text() {
            return String.join("\n\n", message.blocks().stream().filter(Text.class::isInstance).map(b -> ((Text) b).text())
                    .filter(t -> !t.isBlank()).toList());
        }
    }
}
