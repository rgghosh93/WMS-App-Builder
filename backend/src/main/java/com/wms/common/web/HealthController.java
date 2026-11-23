package com.wms.common.web;

import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Whether the server is up and can reach its database. Open to all, and says nothing more. */
@RestController
public class HealthController {

    private final JdbcTemplate db;

    public HealthController(JdbcTemplate db) {
        this.db = db;
    }

    @GetMapping("/api/health")
    public Map<String, String> health() {
        db.queryForObject("select 1", Integer.class);
        return Map.of("status", "UP");
    }
}
