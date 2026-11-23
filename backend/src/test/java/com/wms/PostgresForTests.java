package com.wms;

import java.io.IOException;
import java.io.UncheckedIOException;

import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

/**
 * With {@code -Dwms.test.postgres=true}, the tests run against a real PostgreSQL instead of H2: one is started before
 * any test and stopped after the last. It needs no Docker or install, so it runs the same here and in CI. Tests that
 * name a database of their own keep it.
 */
public class PostgresForTests implements LauncherSessionListener {

    private EmbeddedPostgres postgres;

    @Override
    public void launcherSessionOpened(LauncherSession session) {
        if (!Boolean.getBoolean("wms.test.postgres") || postgres != null) {
            return;
        }
        try {
            postgres = EmbeddedPostgres.builder().start();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not start PostgreSQL for the tests", e);
        }
        System.setProperty("spring.datasource.url", postgres.getJdbcUrl("postgres", "postgres"));
        System.setProperty("spring.datasource.username", "postgres");
        System.setProperty("spring.datasource.password", "postgres");
    }

    @Override
    public void launcherSessionClosed(LauncherSession session) {
        if (postgres != null) {
            try {
                postgres.close();
            } catch (IOException e) {
                // It goes with the JVM anyway.
            }
            postgres = null;
        }
    }
}
