package com.wms.builder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One change request's own checkout of the WMS, on a branch of its own. The agent reads and changes files only through
 * here, and only where it is allowed to (GR-7): never git's own files, never the builder itself, the deployment set-up or
 * the base database migrations.
 */
public class Workspace {

    /** What the agent may not change: how the WMS is built and deployed, and the builder, which must not widen its own reach. */
    static final List<String> PROTECTED = List.of(
            ".github/", ".mvn/", "render.yaml", "Dockerfile", ".dockerignore", ".gitignore", "backend/mvnw", "backend/mvnw.cmd",
            "backend/pom.xml", "backend/src/main/java/com/wms/builder/", "backend/src/test/java/com/wms/builder/",
            "frontend/src/pages/builder/", "frontend/src/api/builder.ts", "backend/src/main/resources/application-cloud.properties");

    static final String MIGRATIONS = "backend/src/main/resources/db/migration/";
    /** The builder's own migrations are numbered from here, so they never clash with the base's. */
    static final int FIRST_BUILDER_MIGRATION = 1000;
    private static final Pattern MIGRATION = Pattern.compile("V(\\d+)__[A-Za-z0-9_]+\\.sql");

    static final int MAX_WRITE = 256 * 1024;
    static final int MAX_LINES = 2000;
    static final int MAX_LIST = 400;
    static final int MAX_MATCHES = 200;

    private final Path root;
    /** The commit this change started from. */
    private final String startedAt;
    /** The change's own branch. */
    private final String branch;

    private Workspace(Path root, String startedAt, String branch) {
        this.root = root;
        this.startedAt = startedAt;
        this.branch = branch;
    }

    /** The checkout at {@code dir}, made from {@code source} on a new {@code branch} the first time. */
    public static Workspace open(Path dir, String source, String baseBranch, String branch) {
        Path root = dir.toAbsolutePath().normalize();
        if (!Files.isDirectory(root.resolve(".git"))) {
            try {
                Files.createDirectories(root.getParent());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            String from = source.contains("://") ? source : Path.of(source).toAbsolutePath().normalize().toString();
            git(root.getParent(), Duration.ofMinutes(10), "-c", "core.autocrlf=false", "clone", "--quiet", "--branch", baseBranch,
                    "--single-branch", from, root.getFileName().toString());
            git(root, "config", "core.autocrlf", "false");
            git(root, "config", "user.name", "WMS App Builder");
            git(root, "config", "user.email", "app-builder@wms.invalid");
            git(root, "config", "wms.start", git(root, "rev-parse", "HEAD").strip());
            git(root, "checkout", "--quiet", "-b", branch);
        }
        return new Workspace(root, startedAt(root), branch);
    }

    /**
     * The commit a checkout started from, as it recorded. Checkouts made before that was recorded started from the one
     * branch they were cloned with, so it is worked out from that and recorded.
     */
    private static String startedAt(Path root) {
        Result recorded = run(root, Duration.ofSeconds(30), "git", "config", "--get", "wms.start");
        if (recorded.code() == 0 && !recorded.output().isBlank()) {
            return recorded.output().strip();
        }
        List<String> remote = lines(git(root, "for-each-ref", "--format=%(refname)", "refs/remotes/origin")).stream()
                .filter(name -> !name.endsWith("/HEAD")).toList();
        if (remote.isEmpty()) {
            return null; // still being made
        }
        String start = git(root, "merge-base", remote.getFirst(), "HEAD").strip();
        git(root, "config", "wms.start", start);
        return start;
    }

    public Path root() {
        return root;
    }

    // --- What the agent may do -------------------------------------------------------------------------------------

    public String list(String under) {
        String where = under == null || under.isBlank() ? "." : relative(under);
        List<String> files = lines(git(root, "ls-files", "--cached", "--others", "--exclude-standard", "--", where));
        if (files.isEmpty()) {
            return "No files under " + where;
        }
        String shown = String.join("\n", files.subList(0, Math.min(files.size(), MAX_LIST)));
        return files.size() > MAX_LIST ? shown + "\n… and " + (files.size() - MAX_LIST) + " more; list a folder further down" : shown;
    }

    public String read(String path, Integer startLine, Integer lineCount) {
        Path file = resolve(path);
        if (!Files.isRegularFile(file)) {
            throw new Refused("There is no file " + path + ". Use list_files to see what there is.");
        }
        List<String> all;
        try {
            all = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new Refused(path + " isn't a text file");
        }
        int from = Math.max(1, startLine == null ? 1 : startLine);
        int count = Math.min(MAX_LINES, lineCount == null || lineCount < 1 ? MAX_LINES : lineCount);
        StringBuilder out = new StringBuilder();
        int to = Math.min(all.size(), from - 1 + count);
        for (int i = from - 1; i < to; i++) {
            out.append(i + 1).append('\t').append(all.get(i)).append('\n');
        }
        if (to < all.size()) {
            out.append("… ").append(all.size() - to).append(" more lines; read on from line ").append(to + 1);
        }
        return all.isEmpty() ? "(empty file)" : out.toString();
    }

    public String search(String pattern, String under) {
        if (pattern == null || pattern.isBlank()) {
            throw new Refused("Say what to search for");
        }
        String where = under == null || under.isBlank() ? "." : relative(under);
        Result found = run(root, Duration.ofMinutes(1), "git", "--no-optional-locks", "grep", "-n", "-I", "--untracked", "-E", "-e",
                pattern, "--", where);
        if (found.code() == 1) {
            return "Nothing matches " + pattern;
        }
        if (found.code() != 0) {
            throw new Refused("Couldn't search for that: " + found.output().strip());
        }
        List<String> matches = lines(found.output());
        String shown = String.join("\n", matches.subList(0, Math.min(matches.size(), MAX_MATCHES)));
        return matches.size() > MAX_MATCHES ? shown + "\n… and " + (matches.size() - MAX_MATCHES) + " more matches; narrow the search"
                : shown;
    }

    public String write(String path, String content) {
        Path file = writable(path);
        if (content == null) {
            throw new Refused("Say what to write");
        }
        if (content.getBytes(StandardCharsets.UTF_8).length > MAX_WRITE) {
            throw new Refused("That is too big for one file (more than " + MAX_WRITE / 1024 + " KB); split it up");
        }
        boolean existed = Files.exists(file);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new Refused("Couldn't write " + path + ": " + e.getMessage());
        }
        return (existed ? "Replaced " : "Created ") + relative(path);
    }

    public String edit(String path, String oldText, String newText, boolean all) {
        Path file = writable(path);
        if (!Files.isRegularFile(file)) {
            throw new Refused("There is no file " + path + "; use write_file to create it");
        }
        if (oldText == null || oldText.isEmpty()) {
            throw new Refused("Say which text to replace");
        }
        String content;
        try {
            content = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new Refused(path + " isn't a text file");
        }
        int times = count(content, oldText);
        if (times == 0) {
            throw new Refused("That text isn't in " + path + ". Read the file again and copy the text exactly, spaces included.");
        }
        if (times > 1 && !all) {
            throw new Refused("That text is in " + path + " " + times + " times. Include more of what is around it, or set replace_all.");
        }
        String changed = all ? content.replace(oldText, newText == null ? "" : newText)
                : content.replaceFirst(Pattern.quote(oldText), Matcher.quoteReplacement(newText == null ? "" : newText));
        try {
            Files.writeString(file, changed, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new Refused("Couldn't write " + path + ": " + e.getMessage());
        }
        return "Changed " + relative(path) + (times > 1 ? " in " + times + " places" : "");
    }

    // --- For the builder itself ------------------------------------------------------------------------------------

    /** Commits whatever changed. False if nothing had. */
    public boolean commit(String message) {
        git(root, "add", "--all");
        if (git(root, "status", "--porcelain").isBlank()) {
            return false;
        }
        git(root, "commit", "--quiet", "-m", message);
        return true;
    }

    /** The files this change differs in from where it started, as "M path", "A path" or "D path". */
    public List<String> changedFiles() {
        if (startedAt == null) {
            return List.of();
        }
        String committed = git(root, "--no-optional-locks", "diff", "--name-status", startedAt);
        String untracked = git(root, "--no-optional-locks", "ls-files", "--others", "--exclude-standard");
        List<String> changed = new ArrayList<>(lines(committed).stream().map(l -> l.replace('\t', ' ')).toList());
        lines(untracked).forEach(path -> changed.add("A " + path));
        return changed;
    }

    /** What builds and previews leave in the checkout; none of it is part of the change. */
    static final List<String> BUILDS = List.of("frontend/node_modules", "frontend/dist", "backend/target", "backend/data",
            "backend/src/main/resources/static");

    /** Deletes what building, testing and previewing the change left behind, to free the space. */
    public void clearBuilds() {
        for (String build : BUILDS) {
            Path dir = root.resolve(build);
            if (!Files.exists(dir)) {
                continue;
            }
            try (java.util.stream.Stream<Path> files = Files.walk(dir)) {
                for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    file.toFile().setWritable(true);
                    Files.deleteIfExists(file);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /** The commit the change is at. */
    public String head() {
        return git(root, "--no-optional-locks", "rev-parse", "HEAD").strip();
    }

    /**
     * Merges the change into {@code target} on the source repository and pushes it there, so the environment that runs
     * {@code target} gets it. The checkout stays on the change's own branch.
     *
     * @return the merge commit on {@code target}, or empty if {@code target} already had everything in the change
     * @throws Clash if the change and what is on {@code target} change the same lines
     */
    public java.util.Optional<String> accept(String target, String message) {
        Result fetched = run(root, Duration.ofMinutes(5), "git", "fetch", "--quiet", "origin",
                "+refs/heads/" + target + ":refs/remotes/origin/" + target);
        if (fetched.code() != 0) {
            throw new Refused("Couldn't get the " + target + " branch to accept into: " + fetched.output().strip());
        }
        String into = "refs/remotes/origin/" + target;
        if (run(root, Duration.ofSeconds(30), "git", "merge-base", "--is-ancestor", "HEAD", into).code() == 0) {
            return java.util.Optional.empty();
        }
        git(root, "checkout", "--quiet", "--detach", into);
        try {
            Result merged = run(root, Duration.ofMinutes(2), "git", "merge", "--no-ff", "--no-edit", "-m", message, branch);
            if (merged.code() != 0) {
                run(root, Duration.ofSeconds(30), "git", "merge", "--abort");
                List<String> clashing = lines(merged.output()).stream().filter(l -> l.startsWith("CONFLICT"))
                        .map(l -> l.replaceFirst("^.*Merge conflict in ", "")).toList();
                throw new Clash(clashing);
            }
            String commit = head();
            Result pushed = run(root, Duration.ofMinutes(5), "git", "push", "--quiet", "origin", "HEAD:refs/heads/" + target);
            if (pushed.code() != 0) {
                throw new Refused("Couldn't put it on " + target + ": " + pushed.output().strip());
            }
            return java.util.Optional.of(commit);
        } finally {
            git(root, "checkout", "--quiet", "--force", branch);
        }
    }

    /** Runs a command in the checkout, for the tests. */
    Result run(Duration limit, List<String> command, String inFolder) {
        return run(resolve(inFolder), limit, command.toArray(String[]::new));
    }

    // --- Guardrails ------------------------------------------------------------------------------------------------

    /** The path inside the checkout; outside it, or in git's own files, is refused. */
    Path resolve(String path) {
        String relative = relative(path);
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root)) {
            throw new Refused(path + " is outside the WMS code");
        }
        for (Path part : root.relativize(resolved)) {
            if (part.toString().equals(".git")) {
                throw new Refused("git's own files are off limits");
            }
        }
        return resolved;
    }

    private Path writable(String path) {
        Path file = resolve(path);
        String relative = root.relativize(file).toString().replace('\\', '/');
        for (String off : PROTECTED) {
            if (relative.equals(off) || off.endsWith("/") && relative.startsWith(off)) {
                throw new Refused(relative + " can't be changed by the App Builder");
            }
        }
        if (relative.startsWith(MIGRATIONS)) {
            Matcher name = MIGRATION.matcher(relative.substring(MIGRATIONS.length()));
            if (!name.matches()) {
                throw new Refused("Migrations are named like V1001__what_it_does.sql");
            }
            if (Integer.parseInt(name.group(1)) < FIRST_BUILDER_MIGRATION) {
                throw new Refused("The base WMS's migrations can't be changed, and new ones are numbered from V"
                        + FIRST_BUILDER_MIGRATION + "__");
            }
        }
        return file;
    }

    private static String relative(String path) {
        if (path == null || path.isBlank()) {
            throw new Refused("Say which file");
        }
        String clean = path.strip().replace('\\', '/');
        while (clean.startsWith("./")) {
            clean = clean.substring(2);
        }
        if (clean.startsWith("/") || clean.matches("^[A-Za-z]:.*")) {
            throw new Refused("Give paths from the top of the WMS code, like backend/pom.xml");
        }
        return clean;
    }

    private static int count(String text, String part) {
        int times = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + part.length())) {
            times++;
        }
        return times;
    }

    private static List<String> lines(String output) {
        return output.lines().filter(l -> !l.isBlank()).toList();
    }

    // --- Running commands ------------------------------------------------------------------------------------------

    record Result(int code, String output) {
    }

    private static String git(Path in, String... args) {
        return git(in, Duration.ofMinutes(2), args);
    }

    private static String git(Path in, Duration limit, String... args) {
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        Result result = run(in, limit, command);
        if (result.code() != 0) {
            throw new IllegalStateException("git " + args[0] + " failed: " + result.output().strip());
        }
        return result.output();
    }

    private static Result run(Path in, Duration limit, String... command) {
        try {
            Path log = Files.createTempFile("wms-builder", ".log");
            try {
                Process process = new ProcessBuilder(command).directory(in.toFile()).redirectErrorStream(true)
                        .redirectOutput(log.toFile()).redirectInput(ProcessBuilder.Redirect.from(nullDevice())).start();
                if (!process.waitFor(limit.toSeconds(), TimeUnit.SECONDS)) {
                    process.descendants().forEach(ProcessHandle::destroyForcibly);
                    process.destroyForcibly();
                    return new Result(-1, "Stopped after " + limit.toMinutes() + " minutes\n" + Files.readString(log));
                }
                return new Result(process.exitValue(), new String(Files.readAllBytes(log), StandardCharsets.UTF_8));
            } finally {
                Files.deleteIfExists(log);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(-1, "Interrupted");
        }
    }

    private static java.io.File nullDevice() {
        return new java.io.File(System.getProperty("os.name").startsWith("Windows") ? "NUL" : "/dev/null");
    }

    /** The change and what it is being merged into change the same lines. */
    public static class Clash extends RuntimeException {
        private final List<String> files;

        Clash(List<String> files) {
            super("It clashes with changes already there" + (files.isEmpty() ? "" : ", in " + String.join(", ", files)));
            this.files = List.copyOf(files);
        }

        public List<String> files() {
            return files;
        }
    }

    /** The agent asked for something it may not do, or that can't be done; it is told why and can try another way. */
    public static class Refused extends RuntimeException {
        public Refused(String message) {
            super(message);
        }
    }
}
