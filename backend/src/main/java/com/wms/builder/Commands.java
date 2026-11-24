package com.wms.builder;

import java.util.ArrayList;
import java.util.List;

/** How to run the build tools in a checkout, on Windows and elsewhere. */
final class Commands {

    static final boolean WINDOWS = System.getProperty("os.name").startsWith("Windows");

    private Commands() {
    }

    /** The Maven wrapper in the checkout's backend, by its full path: Windows may not run programs from the current folder by name. */
    static List<String> maven(Workspace workspace, String... args) {
        List<String> command = new ArrayList<>(WINDOWS ? List.of("cmd", "/c", workspace.resolve("backend/mvnw.cmd").toString())
                : List.of("sh", "mvnw"));
        command.addAll(List.of(args));
        return command;
    }

    /** npm or npx, which are scripts on Windows. */
    static List<String> node(String... command) {
        List<String> all = new ArrayList<>();
        if (WINDOWS) {
            all.addAll(List.of("cmd", "/c"));
        }
        all.addAll(List.of(command));
        return all;
    }
}
