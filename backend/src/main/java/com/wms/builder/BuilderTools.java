package com.wms.builder;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.wms.builder.llm.Llm;

/**
 * What the agent can do in a change request's workspace (AR-1): look around the code, change it, and run the tests. Every
 * call is answered, refused ones with the reason, so the agent can try another way.
 */
class BuilderTools {

    static final String LIST = "list_files";
    static final String READ = "read_file";
    static final String SEARCH = "search_code";
    static final String WRITE = "write_file";
    static final String EDIT = "edit_file";
    static final String CHECKS = "run_checks";
    static final String ASK = "ask_questions";
    static final String REPORT = "report_change";
    /** What every working step says it is for, in plain words: what people see of it. */
    static final String PURPOSE = "purpose";
    static final List<String> RESULTS = List.of("passed", "failed", "not_checked");
    static final int MAX_QUESTIONS = 4;
    static final int MAX_OPTIONS = 6;

    private static final Pattern TEST_FILTER = Pattern.compile("[A-Za-z0-9_.*#,/-]{1,200}");
    private static final int MAX_OUTPUT_LINES = 150;

    private final boolean checks;

    BuilderTools(boolean checks) {
        this.checks = checks;
    }

    List<Llm.Tool> definitions() {
        List<Llm.Tool> tools = new ArrayList<>(List.of(
                tool(LIST, "List the files in the WMS code, or in one folder of it. Paths are from the top of the code, like "
                        + "backend/src/main/java/com/wms/inbound.", Map.of("path", text("Folder to list; everything if left out")),
                        List.of()),
                tool(READ, "Read a file, with line numbers. Long files come in parts: give start_line to read on.",
                        Map.of("path", text("The file, from the top of the code"),
                                "start_line", number("First line to read (1 if left out)"),
                                "line_count", number("How many lines (up to 2000)")), List.of("path")),
                tool(SEARCH, "Search the code with a regular expression (POSIX extended, like git grep -E). Gives file:line:text.",
                        Map.of("pattern", text("What to look for"), "path", text("Folder or file to search in; everything if left out")),
                        List.of("pattern")),
                tool(WRITE, "Create a file, or replace all of one. For changes to an existing file, edit_file is better.",
                        Map.of("path", text("The file, from the top of the code"), "content", text("Everything the file should hold")),
                        List.of("path", "content")),
                tool(EDIT, "Replace text in a file. old_text must match the file exactly, spaces and line breaks included, and "
                        + "only once unless replace_all is set.",
                        Map.of("path", text("The file, from the top of the code"), "old_text", text("The text as it is now"),
                                "new_text", text("What it should be"),
                                "replace_all", new java.util.TreeMap<>(Map.of("type", "boolean", "description", "Replace every place it appears"))),
                        List.of("path", "old_text", "new_text"))));
        tools.add(tool(ASK, "Ask the person up to four questions before building, shown to them as a form with choices. "
                + "Use it on its own, never with another tool in the same turn; the run waits for their answers, which come "
                + "back as this tool's result.",
                Map.of("intro", text("One short sentence before the questions, if it helps"),
                        "questions", new java.util.TreeMap<>(Map.of("type", "array", "minItems", 1, "maxItems", MAX_QUESTIONS,
                                "description", "The questions, most important first",
                                "items", new java.util.TreeMap<>(Map.of("type", "object", "required", List.of("question"),
                                        "properties", new java.util.TreeMap<>(Map.of(
                                                "question", text("The question, in warehouse terms"),
                                                "options", new java.util.TreeMap<>(Map.of("type", "array", "maxItems", MAX_OPTIONS,
                                                        "description", "Choices to pick from; leave out for a question answered "
                                                                + "in words. They can always add their own answer too.",
                                                        "items", new java.util.TreeMap<>(Map.of("type", "object", "required",
                                                                List.of("label"), "properties", new java.util.TreeMap<>(Map.of(
                                                                        "label", text("The choice, a few words"),
                                                                        "description", text("What it means, if not plain"))))))),
                                                "multiple", new java.util.TreeMap<>(Map.of("type", "boolean",
                                                        "description", "More than one choice can be picked")),
                                                "recommended", text("The label of the choice you recommend")))))))),
                List.of("questions")));
        tools.add(tool(REPORT, "Finish with a summary of what you built, for the person who asked: shown to them as a page "
                + "in warehouse terms, never code. It ends your turn.",
                Map.of("headline", text("One or two sentences: what people can now do, and whether it is ready"),
                        "changes", list("What changed, one entry per part of the WMS people will find it in",
                                object(Map.of("area", text("Where in the WMS, like \"Locations page\" or \"Handheld Move\""),
                                        "what", text("What is different there, in a sentence or two"))), List.of("area", "what")),
                        "try_it", list("How to try it, step by step", text("One step"), null),
                        "checklist", list("What the change should do, each with its result",
                                object(Map.of("item", text("Something the change should do"),
                                        "result", new java.util.TreeMap<>(Map.of("type", "string", "enum", RESULTS,
                                                "description", "passed or failed by a check you ran, or not_checked")),
                                        "note", text("Why, if it isn't plain"))), List.of("item", "result")),
                        "left_out", list("What was asked or would be expected but isn't in this change", text("One thing"), null)),
                List.of("headline", "changes", "checklist")));
        if (checks) {
            tools.add(tool(CHECKS, "Run the tests and say what failed. backend compiles the server and runs its JUnit tests (several "
                    + "minutes for all of them; give only to run some). frontend type-checks the screens and runs their Vitest tests.",
                    Map.of("which", new java.util.TreeMap<>(Map.of("type", "string", "enum", List.of("backend", "frontend"),
                                    "description", "Which tests")),
                            "only", text("backend: test classes, like ReceivingTest or Asn*Test. frontend: test files, like "
                                    + "Receiving. Everything if left out.")),
                    List.of("which")));
        }
        return tools.stream().map(t -> t.name().equals(ASK) || t.name().equals(REPORT) ? t : withPurpose(t)).toList();
    }

    /** Does what the agent asked and says what came of it. */
    Llm.ToolResult run(Workspace workspace, Llm.ToolCall call) {
        Map<String, Object> in = call.input();
        try {
            String output = switch (call.name()) {
                case LIST -> workspace.list(string(in, "path"));
                case READ -> workspace.read(string(in, "path"), integer(in, "start_line"), integer(in, "line_count"));
                case SEARCH -> workspace.search(string(in, "pattern"), string(in, "path"));
                case WRITE -> workspace.write(string(in, "path"), string(in, "content"));
                case EDIT -> workspace.edit(string(in, "path"), string(in, "old_text"), string(in, "new_text"),
                        Boolean.TRUE.equals(in.get("replace_all")));
                case REPORT -> throw new Workspace.Refused(java.util.Objects.requireNonNullElse(Report.problem(in),
                        "report_change ends the turn"));
                case ASK -> throw new Workspace.Refused(java.util.Objects.requireNonNullElse(Questions.problem(in),
                        "ask_questions is used on its own, with no other tool in the same turn"));
                case CHECKS -> {
                    if (!checks) {
                        throw new Workspace.Refused("Running the tests is switched off here");
                    }
                    yield checks(workspace, string(in, "which"), string(in, "only"));
                }
                default -> throw new Workspace.Refused("There is no tool called " + call.name());
            };
            return new Llm.ToolResult(call.id(), output, false);
        } catch (Workspace.Refused e) {
            return new Llm.ToolResult(call.id(), e.getMessage(), true);
        }
    }

    private String checks(Workspace workspace, String which, String only) {
        if (only != null && !only.isBlank() && !TEST_FILTER.matcher(only).matches()) {
            throw new Workspace.Refused("Name tests with letters, digits and * only");
        }
        boolean some = only != null && !only.isBlank();
        List<Workspace.Result> steps = new ArrayList<>();
        if ("backend".equals(which)) {
            List<String> mvn = Commands.maven(workspace, "-B", "-q", "test");
            if (some) {
                mvn.addAll(List.of("-Dtest=" + only, "-Dsurefire.failIfNoSpecifiedTests=false"));
            }
            steps.add(workspace.run(Duration.ofMinutes(25), mvn, "backend"));
        } else if ("frontend".equals(which)) {
            if (!java.nio.file.Files.isDirectory(workspace.resolve("frontend/node_modules"))) {
                Workspace.Result install = workspace.run(Duration.ofMinutes(10), npm("npm", "ci", "--no-audit", "--no-fund"), "frontend");
                if (install.code() != 0) {
                    return outcome("Installing what the screens need failed", install);
                }
            }
            Workspace.Result types = workspace.run(Duration.ofMinutes(5), npm("npx", "tsc", "--noEmit", "-p", "."), "frontend");
            if (types.code() != 0) {
                return outcome("The screens don't type-check", types);
            }
            List<String> vitest = new ArrayList<>(npm("npx", "vitest", "run"));
            if (some) {
                vitest.add(only);
            }
            steps.add(workspace.run(Duration.ofMinutes(10), vitest, "frontend"));
        } else {
            throw new Workspace.Refused("which is backend or frontend");
        }
        Workspace.Result last = steps.getLast();
        return last.code() == 0 ? "Passed.\n" + tail(last.output()) : outcome("Failed", last);
    }

    private static List<String> npm(String... command) {
        return Commands.node(command);
    }

    private static String outcome(String what, Workspace.Result result) {
        return what + " (exit " + result.code() + ").\n" + tail(result.output());
    }

    private static String tail(String output) {
        List<String> lines = output.lines().toList();
        if (lines.size() <= MAX_OUTPUT_LINES) {
            return output.strip();
        }
        return "… " + (lines.size() - MAX_OUTPUT_LINES) + " lines before this\n"
                + String.join("\n", lines.subList(lines.size() - MAX_OUTPUT_LINES, lines.size()));
    }

    /** Checks the agent's summary: what is wrong with a report_change call, or null if nothing is. */
    static final class Report {
        private Report() {
        }

        static String problem(Map<String, Object> input) {
            if (!(input.get("headline") instanceof String headline) || headline.isBlank()) {
                return "Give a headline";
            }
            if (!(input.get("changes") instanceof List<?> changes) || changes.isEmpty()) {
                return "Say what changed, at least one entry";
            }
            for (Object change : changes) {
                if (!(change instanceof Map<?, ?> each) || !(each.get("area") instanceof String) || !(each.get("what") instanceof String)) {
                    return "Each change needs an area and what changed there";
                }
            }
            if (!(input.get("checklist") instanceof List<?> checklist)) {
                return "Give a checklist";
            }
            for (Object item : checklist) {
                if (!(item instanceof Map<?, ?> each) || !(each.get("item") instanceof String) || !RESULTS.contains(each.get("result"))) {
                    return "Each checklist item needs the item and a result: passed, failed or not_checked";
                }
            }
            for (String strings : List.of("try_it", "left_out")) {
                Object value = input.get(strings);
                if (value != null && !(value instanceof List<?> list && list.stream().allMatch(String.class::isInstance))) {
                    return strings + " is a list of sentences";
                }
            }
            return null;
        }
    }

    /** Questions the agent asks the person, read from an ask_questions call. */
    record Questions(String intro, List<Question> questions) {

        record Question(String question, List<Option> options, boolean multiple, String recommended) {
        }

        record Option(String label, String description) {
        }

        /** What is wrong with an ask_questions call, or null if nothing is. */
        static String problem(Map<String, Object> input) {
            if (!(input.get("questions") instanceof List<?> list) || list.isEmpty()) {
                return "Give at least one question";
            }
            if (list.size() > MAX_QUESTIONS) {
                return "Ask at most " + MAX_QUESTIONS + " questions at a time; ask the most important ones first";
            }
            for (Object each : list) {
                if (!(each instanceof Map<?, ?> question) || !(question.get("question") instanceof String text) || text.isBlank()) {
                    return "Each question needs its question";
                }
                Object options = question.get("options");
                if (options != null && !(options instanceof List<?>)) {
                    return "options is a list of choices";
                }
                if (options instanceof List<?> choices) {
                    if (choices.size() > MAX_OPTIONS) {
                        return "Give at most " + MAX_OPTIONS + " choices for a question";
                    }
                    for (Object choice : choices) {
                        if (!(choice instanceof Map<?, ?> option) || !(option.get("label") instanceof String label) || label.isBlank()) {
                            return "Each choice needs a label";
                        }
                    }
                }
            }
            return null;
        }

        /** The questions in a call that has no {@link #problem}. */
        static Questions of(Map<String, Object> input) {
            List<Question> questions = new ArrayList<>();
            for (Object each : (List<?>) input.get("questions")) {
                Map<?, ?> question = (Map<?, ?>) each;
                List<Option> options = new ArrayList<>();
                if (question.get("options") instanceof List<?> choices) {
                    for (Object choice : choices) {
                        Map<?, ?> option = (Map<?, ?>) choice;
                        options.add(new Option(((String) option.get("label")).strip(),
                                option.get("description") instanceof String d && !d.isBlank() ? d.strip() : null));
                    }
                }
                String recommended = question.get("recommended") instanceof String r
                        && options.stream().anyMatch(o -> o.label().equals(r.strip())) ? r.strip() : null;
                questions.add(new Question(((String) question.get("question")).strip(), options,
                        Boolean.TRUE.equals(question.get("multiple")) && !options.isEmpty(), recommended));
            }
            return new Questions(input.get("intro") instanceof String intro && !intro.isBlank() ? intro.strip() : null, questions);
        }
    }

    /** Adds the plain-words purpose every working step must give. */
    @SuppressWarnings("unchecked")
    private static Llm.Tool withPurpose(Llm.Tool tool) {
        Map<String, Object> schema = new LinkedHashMap<>(tool.inputSchema());
        Map<String, Object> properties = new java.util.TreeMap<>((Map<String, Object>) schema.get("properties"));
        properties.put(PURPOSE, text("What you are doing and why, for the person who asked: one short sentence in warehouse "
                + "terms, like \"Checking how put-away chooses a location\" or \"Adding Staging to the location types\". "
                + "Never file names or code."));
        List<String> required = new ArrayList<>();
        required.add(PURPOSE);
        required.addAll((List<String>) schema.get("required"));
        schema.put("properties", properties);
        schema.put("required", required);
        return new Llm.Tool(tool.name(), tool.description(), schema);
    }

    private static Llm.Tool tool(String name, String description, Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", new java.util.TreeMap<>(properties));
        schema.put("required", required);
        return new Llm.Tool(name, description, schema);
    }

    private static Map<String, Object> list(String description, Map<String, Object> items, List<String> required) {
        Map<String, Object> each = new java.util.TreeMap<>(items);
        if (required != null) {
            each.put("required", required);
        }
        return new java.util.TreeMap<>(Map.of("type", "array", "description", description, "items", each));
    }

    private static Map<String, Object> object(Map<String, Object> properties) {
        return new java.util.TreeMap<>(Map.of("type", "object", "properties", new java.util.TreeMap<>(properties)));
    }

    private static Map<String, Object> text(String description) {
        return new java.util.TreeMap<>(Map.of("type", "string", "description", description));
    }

    private static Map<String, Object> number(String description) {
        return new java.util.TreeMap<>(Map.of("type", "integer", "description", description));
    }

    private static String string(Map<String, Object> in, String key) {
        Object value = in.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static Integer integer(Map<String, Object> in, String key) {
        Object value = in.get(key);
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return value == null ? null : Integer.valueOf(String.valueOf(value).strip());
        } catch (NumberFormatException e) {
            throw new Workspace.Refused(key + " should be a whole number");
        }
    }
}
