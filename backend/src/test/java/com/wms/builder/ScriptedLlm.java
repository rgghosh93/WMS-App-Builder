package com.wms.builder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import com.wms.builder.llm.Llm;
import com.wms.builder.llm.LlmGateway;

/** Stands in for Claude in tests: answers each call with the next reply it was given, and keeps what it was sent. */
class ScriptedLlm implements LlmGateway {

    private final BlockingQueue<Supplier<Llm.Reply>> replies = new LinkedBlockingQueue<>();
    final List<Llm.Request> requests = new CopyOnWriteArrayList<>();

    @Override
    public String model() {
        return "scripted";
    }

    @Override
    public Llm.Reply send(Llm.Request request) {
        requests.add(request);
        try {
            Supplier<Llm.Reply> next = replies.poll(10, TimeUnit.SECONDS);
            if (next == null) {
                throw new LlmException("The script ran out", null);
            }
            return next.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("Interrupted", e);
        }
    }

    void then(Supplier<Llm.Reply> reply) {
        replies.add(reply);
    }

    void says(String text) {
        then(() -> reply(Llm.Stop.DONE, List.of(new Llm.Text(text))));
    }

    void uses(String text, Llm.ToolCall... calls) {
        List<Llm.Block> blocks = new ArrayList<>();
        if (text != null) {
            blocks.add(new Llm.Text(text));
        }
        blocks.addAll(List.of(calls));
        then(() -> reply(Llm.Stop.TOOL_USE, blocks));
    }

    void reset() {
        replies.clear();
        requests.clear();
    }

    static Llm.ToolCall call(String id, String name, Map<String, Object> input) {
        return new Llm.ToolCall(id, name, input);
    }

    static Llm.Reply reply(Llm.Stop stop, List<Llm.Block> blocks) {
        return new Llm.Reply(Llm.Message.assistant(blocks), stop, new Llm.Usage(100, 10), "scripted", null);
    }
}
