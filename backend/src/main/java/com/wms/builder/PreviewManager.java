package com.wms.builder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.wms.common.api.ApiException;

import jakarta.annotation.PreDestroy;

/**
 * Previews: a change request's own copy of the WMS, built and started on this computer with a fresh demo warehouse, so
 * the change can be tried before it is accepted (AB-5). One per change request, on a port of its own. Each is reached at
 * 127.0.0.1 rather than localhost, so its sign-in doesn't replace the one for the WMS itself.
 */
@Component
public class PreviewManager {

    private static final Logger log = LoggerFactory.getLogger(PreviewManager.class);
    static final Duration STARTING_LIMIT = Duration.ofMinutes(8);

    public enum State { STARTING, RUNNING, FAILED }

    /**
     * Where a preview has got to.
     *
     * @param commit   the change's commit it was started from
     * @param upToDate it shows the change as it is now
     * @param step     what it is doing while starting
     * @param problem  why it failed
     */
    public record View(State state, String url, String commit, boolean upToDate, String step, String problem, Instant since,
                       SignIn signIn) {
    }

    /**
     * How to sign in to the preview's desktop: an administrator made for the person who started it, in the preview's own
     * throwaway database. {@code note} says why there is none, if it couldn't be made.
     */
    public record SignIn(String userCode, String password, String note) {
    }

    /** Who starts a preview; they get its administrator. */
    public record Person(String userCode, String displayName) {
    }

    /** Starts the server of a checkout. Replaced in tests. */
    interface Launcher {
        Handle start(Workspace workspace, int port, Path log) throws IOException;
    }

    /** A started preview server. */
    interface Handle {
        boolean isAlive();

        void stop();
    }

    private final BuilderSettings settings;
    private final Map<UUID, Preview> previews = new ConcurrentHashMap<>();
    private final ExecutorService starting = Executors.newCachedThreadPool(Thread.ofPlatform().name("app-builder-preview-", 1).daemon().factory());
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private Launcher launcher = PreviewManager::spring;

    public PreviewManager(BuilderSettings settings) {
        this.settings = settings;
    }

    void useLauncher(Launcher launcher) {
        this.launcher = launcher;
    }

    /** Builds and starts the change request's copy, replacing a preview it already has. */
    public void start(UUID id, String number, Workspace workspace, Person person) {
        if (!settings.previews()) {
            throw ApiException.conflict("Previews can't be started here", Map.of());
        }
        stop(id);
        int port = freePort();
        Preview preview = new Preview(port, workspace.head(), settings.workdir().resolve(number + "-preview.log"), person);
        previews.put(id, preview);
        starting.submit(() -> preview.run(workspace));
    }

    public void stop(UUID id) {
        Preview preview = previews.remove(id);
        if (preview != null) {
            preview.stop();
        }
    }

    /** The change request's preview, if it has one; {@code head} is the change's commit now. */
    public View view(UUID id, String head) {
        Preview preview = previews.get(id);
        if (preview == null) {
            return null;
        }
        return new View(preview.state, preview.state == State.FAILED ? null : "http://127.0.0.1:" + preview.port, preview.commit,
                preview.commit.equals(head), preview.step, preview.problem, preview.since, preview.signIn);
    }

    @PreDestroy
    void stopAll() {
        previews.keySet().forEach(this::stop);
        starting.shutdownNow();
    }

    private synchronized int freePort() {
        for (int port = settings.firstPreviewPort(); port <= settings.lastPreviewPort(); port++) {
            int candidate = port;
            if (previews.values().stream().noneMatch(p -> p.port == candidate) && free(port)) {
                return port;
            }
        }
        throw ApiException.conflict("All the preview ports (" + settings.previewPorts() + ") are in use. Stop another preview first.",
                Map.of());
    }

    private static boolean free(int port) {
        try (ServerSocket socket = new ServerSocket(port, 1, InetAddress.getLoopbackAddress())) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private final class Preview {
        final int port;
        final String commit;
        final Path log;
        volatile State state = State.STARTING;
        volatile String step = "Getting ready";
        volatile String problem;
        volatile Instant since = Instant.now();
        final Person person;
        volatile Handle handle;
        volatile boolean stopped;
        volatile SignIn signIn;

        Preview(int port, String commit, Path log, Person person) {
            this.port = port;
            this.commit = commit;
            this.log = log;
            this.person = person;
        }

        void run(Workspace workspace) {
            try {
                buildScreens(workspace);
                step = "Starting the server with a fresh demo warehouse";
                deleteTree(workspace.root().resolve("backend/data"));
                if (stopped) {
                    return;
                }
                handle = launcher.start(workspace, port, log);
                Instant until = Instant.now().plus(STARTING_LIMIT);
                while (!stopped && Instant.now().isBefore(until)) {
                    if (!handle.isAlive()) {
                        fail("The preview server stopped while starting.\n" + tail(log));
                        return;
                    }
                    if (healthy()) {
                        step = "Making your sign-in";
                        signIn = administrator();
                        state = State.RUNNING;
                        step = null;
                        since = Instant.now();
                        return;
                    }
                    Thread.sleep(2000);
                }
                if (!stopped) {
                    handle.stop();
                    fail("The preview server didn't start within " + STARTING_LIMIT.toMinutes() + " minutes.\n" + tail(log));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Workspace.Refused e) {
                fail(e.getMessage());
            } catch (IOException | RuntimeException e) {
                PreviewManager.log.warn("Preview on port {} failed", port, e);
                fail("The preview couldn't be started: " + e.getMessage());
            }
        }

        /** The screens are packed into the server, the way the WMS is deployed. */
        private void buildScreens(Workspace workspace) throws IOException {
            if (!Files.exists(workspace.root().resolve("frontend/package.json"))) {
                return;
            }
            if (!Files.isDirectory(workspace.root().resolve("frontend/node_modules"))) {
                step = "Installing what the screens need";
                Workspace.Result installed = workspace.run(Duration.ofMinutes(10), Commands.node("npm", "ci", "--no-audit", "--no-fund"),
                        "frontend");
                if (installed.code() != 0) {
                    throw new Workspace.Refused("Installing what the screens need failed.\n" + tail(installed.output()));
                }
            }
            step = "Building the screens";
            Workspace.Result built = workspace.run(Duration.ofMinutes(5), Commands.node("npx", "vite", "build"), "frontend");
            if (built.code() != 0) {
                throw new Workspace.Refused("The screens didn't build.\n" + tail(built.output()));
            }
            Path into = workspace.root().resolve("backend/src/main/resources/static");
            deleteTree(into);
            Path dist = workspace.root().resolve("frontend/dist");
            try (Stream<Path> files = Files.walk(dist)) {
                for (Path file : files.toList()) {
                    Path target = into.resolve(dist.relativize(file).toString());
                    if (Files.isDirectory(file)) {
                        Files.createDirectories(target);
                    } else {
                        Files.copy(file, target);
                    }
                }
            }
        }

        /**
         * Makes the person who started the preview its first administrator, through the preview's own first-time setup,
         * which it allows from this computer. The password is made up for this preview only.
         */
        private SignIn administrator() {
            String password = password(person.userCode());
            String body = "{\"userCode\":" + json(person.userCode()) + ",\"displayName\":" + json(person.displayName())
                    + ",\"password\":" + json(password) + "}";
            try {
                HttpResponse<String> answer = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/session/setup"))
                        .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
                if (answer.statusCode() == 200) {
                    return new SignIn(person.userCode(), password, null);
                }
                return new SignIn(null, null, answer.statusCode() == 409
                        ? "This preview already has a desktop administrator; sign in as them."
                        : "Couldn't make a sign-in for you (HTTP " + answer.statusCode() + "); create the first administrator yourself.");
            } catch (IOException e) {
                return new SignIn(null, null, "Couldn't make a sign-in for you; create the first administrator yourself.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }

        private boolean healthy() {
            try {
                HttpResponse<Void> answer = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/health"))
                        .timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.discarding());
                return answer.statusCode() == 200;
            } catch (IOException e) {
                return false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        private void fail(String why) {
            if (!stopped) {
                state = State.FAILED;
                step = null;
                problem = why;
                since = Instant.now();
            }
        }

        void stop() {
            stopped = true;
            Handle started = handle;
            if (started != null) {
                started.stop();
            }
        }
    }

    /** The checkout's server through Maven, as a developer runs it, on its own port with the App Builder off. */
    private static Handle spring(Workspace workspace, int port, Path log) throws IOException {
        Files.createDirectories(log.getParent());
        Process process = new ProcessBuilder(Commands.maven(workspace, "-B", "-q", "spring-boot:run",
                "-Dspring-boot.run.arguments=--server.port=" + port + " --wms.builder.enabled=false --wms.demo-data.enabled=true"))
                .directory(workspace.resolve("backend").toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        return new Handle() {
            @Override
            public boolean isAlive() {
                return process.isAlive();
            }

            @Override
            public void stop() {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        };
    }

    /** A password for one preview: random, long enough, and without the user ID in it. */
    static String password(String userCode) {
        String letters = "abcdefghjkmnpqrstuvwxyz23456789";
        java.security.SecureRandom random = new java.security.SecureRandom();
        while (true) {
            StringBuilder password = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                if (i > 0 && i % 4 == 0) {
                    password.append('-');
                }
                password.append(letters.charAt(random.nextInt(letters.length())));
            }
            if (!password.toString().contains(userCode.toLowerCase(java.util.Locale.ROOT))) {
                return password.toString();
            }
        }
    }

    private static String json(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(file);
            }
        }
    }

    private static String tail(Path log) {
        try {
            return Files.exists(log) ? tail(Files.readString(log, StandardCharsets.UTF_8)) : "";
        } catch (IOException | UncheckedIOException e) {
            return "";
        }
    }

    private static String tail(String output) {
        List<String> lines = output.lines().toList();
        return String.join("\n", lines.subList(Math.max(0, lines.size() - 40), lines.size()));
    }
}
