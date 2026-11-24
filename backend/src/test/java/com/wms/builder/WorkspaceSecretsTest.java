package com.wms.builder;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** What the agent runs never sees the server's secrets; only git signs in to GitHub, and only by a header. */
class WorkspaceSecretsTest {

    @AfterEach
    void signOut() {
        Workspace.signInToGitHub(null);
    }

    private static Map<String, String> server() {
        Map<String, String> environment = new HashMap<>();
        environment.put("PATH", "/usr/bin");
        environment.put("HOME", "/home/wms");
        environment.put("ANTHROPIC_API_KEY", "sk-ant-secret");
        environment.put("WMS_BUILDER_GITHUB_TOKEN", "ghp_secret");
        environment.put("WMS_DB_PASSWORD", "pw");
        environment.put("WMS_DB_HOST", "db.internal");
        environment.put("WMS_SETUP_KEY", "setup");
        environment.put("SPRING_DATASOURCE_PASSWORD", "pw");
        environment.put("MAVEN_OPTS", "-Xmx384m");
        return environment;
    }

    @Test
    void buildsAndChecksGetNoSecrets() {
        Workspace.signInToGitHub("ghp_secret");
        Map<String, String> checks = server();
        Workspace.secure(checks, false);
        assertThat(checks).containsOnlyKeys("PATH", "HOME", "MAVEN_OPTS", "GIT_TERMINAL_PROMPT");
        assertThat(checks.values()).noneMatch(v -> v.contains("secret") || v.contains("basic"));
    }

    @Test
    void gitSignsInToGitHubByAHeaderOnly() {
        Workspace.signInToGitHub(" ghp_secret ");
        Map<String, String> git = server();
        Workspace.secure(git, true);
        assertThat(git).doesNotContainKeys("ANTHROPIC_API_KEY", "WMS_BUILDER_GITHUB_TOKEN", "WMS_DB_PASSWORD");
        assertThat(git).containsEntry("GIT_CONFIG_KEY_0", "http.https://github.com/.extraheader");
        assertThat(git.get("GIT_CONFIG_VALUE_0")).isEqualTo("AUTHORIZATION: basic "
                + Base64.getEncoder().encodeToString("x-access-token:ghp_secret".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void aLocalSourceNeedsNoSignIn() {
        Map<String, String> git = server();
        Workspace.secure(git, true);
        assertThat(git).doesNotContainKey("GIT_CONFIG_VALUE_0");
    }
}
