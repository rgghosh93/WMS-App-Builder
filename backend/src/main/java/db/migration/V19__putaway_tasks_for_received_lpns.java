package db.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * Gives LPNs received before put-away existed a put-away task.
 * Written in Java so it works the same on H2 and PostgreSQL.
 */
public class V19__putaway_tasks_for_received_lpns extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection db = context.getConnection();
        Map<UUID, UUID> waiting = new LinkedHashMap<>();
        try (Statement s = db.createStatement();
             ResultSet rs = s.executeQuery("SELECT c.id, c.current_location_id FROM containers c "
                     + "JOIN locations l ON l.id = c.current_location_id WHERE l.location_type = 'RECEIVING' "
                     + "AND c.status = 'IN_USE' AND EXISTS (SELECT 1 FROM inventory i WHERE i.container_id = c.id) ORDER BY c.lpn_id")) {
            while (rs.next()) {
                waiting.put(rs.getObject("id", UUID.class), rs.getObject("current_location_id", UUID.class));
            }
        }
        Timestamp now = Timestamp.from(Instant.now());
        for (Map.Entry<UUID, UUID> lpn : waiting.entrySet()) {
            long number;
            try (Statement s = db.createStatement(); ResultSet rs = s.executeQuery("SELECT nextval('putaway_task_number_seq')")) {
                rs.next();
                number = rs.getLong(1);
            }
            try (PreparedStatement insert = db.prepareStatement("INSERT INTO putaway_tasks (id, task_number, status, "
                    + "container_id, from_location_id, created_at, version) VALUES (?, ?, 'OPEN', ?, ?, ?, 0)")) {
                insert.setObject(1, UUID.randomUUID());
                insert.setString(2, "PA%06d".formatted(number));
                insert.setObject(3, lpn.getKey());
                insert.setObject(4, lpn.getValue());
                insert.setTimestamp(5, now);
                insert.executeUpdate();
            }
        }
    }
}
