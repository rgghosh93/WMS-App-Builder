package com.wms.builder;

import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * How the App Builder is set up. It is off unless {@code wms.builder.enabled} says otherwise, which it should only in the
 * environment changes are built in (wms-builder), never where the WMS is run for real.
 *
 * @param source       the git repository changes start from: a path or a URL
 * @param baseBranch   the branch of it they start from
 * @param acceptBranch the branch of it accepted changes are merged into, which the environment changes are tried out in runs
 * @param workdir      where each change request gets a checkout of its own
 * @param checks       the agent may run the tests
 * @param previews     a change request's own copy of the WMS can be started on this computer to try it
 * @param previewPorts the ports previews may use, like 8090-8094
 * @param githubToken  lets git fetch from and push to a GitHub source; none for a local one
 */
@Component
public record BuilderSettings(
        @Value("${wms.builder.enabled:false}") boolean enabled,
        @Value("${wms.builder.source:..}") String source,
        @Value("${wms.builder.base-branch:builder}") String baseBranch,
        @Value("${wms.builder.accept-branch:builder}") String acceptBranch,
        @Value("${wms.builder.workdir:${java.io.tmpdir}/wms-builder}") Path workdir,
        @Value("${wms.builder.model:claude-opus-5-5}") String model,
        @Value("${wms.builder.effort:high}") String effort,
        @Value("${wms.builder.fallbacks:true}") boolean fallbacks,
        @Value("${wms.builder.max-steps:80}") int maxSteps,
        @Value("${wms.builder.checks:true}") boolean checks,
        @Value("${wms.builder.previews:true}") boolean previews,
        @Value("${wms.builder.preview-ports:8090-8094}") String previewPorts,
        @Value("${wms.builder.github-token:}") String githubToken) {

    /** Keeps the token out of logs and error pages. */
    @Override
    public String toString() {
        return "BuilderSettings[enabled=" + enabled + ", source=" + source + ", baseBranch=" + baseBranch + ", acceptBranch="
                + acceptBranch + "]";
    }

    int firstPreviewPort() {
        return Integer.parseInt(previewPorts.split("-")[0].strip());
    }

    int lastPreviewPort() {
        String[] range = previewPorts.split("-");
        return Integer.parseInt(range[range.length - 1].strip());
    }
}
