package db.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * Pick tasks become a header with details: each allocation is one task detail, and a
 * task groups the details of one order's allocation run.
 * <p>
 * The old pick_tasks table (one row per allocation) becomes pick_task_details. Existing
 * rows are grouped into one new task per order, numbered after its lowest old task number.
 * Written in Java so the regrouping works the same on H2 and PostgreSQL.
 */
public class V9__pick_task_details extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection db = context.getConnection();
        try (Statement s = db.createStatement()) {
            s.execute("ALTER TABLE pick_tasks RENAME TO pick_task_details");
            s.execute("""
                    CREATE TABLE pick_tasks (
                        id           UUID        NOT NULL,
                        task_number  VARCHAR(20) NOT NULL,
                        order_id     UUID        NOT NULL,
                        status       VARCHAR(16) NOT NULL,
                        created_at   TIMESTAMP WITH TIME ZONE NOT NULL,
                        updated_at   TIMESTAMP WITH TIME ZONE NOT NULL,
                        version      BIGINT      NOT NULL,
                        CONSTRAINT pk_pick_task_headers PRIMARY KEY (id),
                        CONSTRAINT uq_pick_task_headers_number UNIQUE (task_number),
                        CONSTRAINT fk_pick_task_headers_order FOREIGN KEY (order_id) REFERENCES outbound_orders (id) ON DELETE CASCADE
                    )""");
            s.execute("CREATE INDEX ix_pick_task_headers_order ON pick_tasks (order_id)");
            s.execute("CREATE INDEX ix_pick_task_headers_status ON pick_tasks (status)");
            s.execute("ALTER TABLE pick_task_details ADD COLUMN pick_task_id UUID");
            s.execute("ALTER TABLE pick_task_details ADD COLUMN detail_number INTEGER");
        }

        regroupExistingTasks(db);

        try (Statement s = db.createStatement()) {
            s.execute("ALTER TABLE pick_task_details ALTER COLUMN pick_task_id SET NOT NULL");
            s.execute("ALTER TABLE pick_task_details ALTER COLUMN detail_number SET NOT NULL");
            s.execute("ALTER TABLE pick_task_details ADD CONSTRAINT fk_pick_task_details_task "
                    + "FOREIGN KEY (pick_task_id) REFERENCES pick_tasks (id) ON DELETE CASCADE");
            s.execute("ALTER TABLE pick_task_details ADD CONSTRAINT uq_pick_task_details_number "
                    + "UNIQUE (pick_task_id, detail_number)");
            s.execute("ALTER TABLE pick_task_details DROP CONSTRAINT uq_pick_tasks_number");
            s.execute("ALTER TABLE pick_task_details DROP COLUMN task_number");
            s.execute("CREATE INDEX ix_pick_task_details_task ON pick_task_details (pick_task_id)");
            s.execute("CREATE INDEX ix_pick_task_details_allocation ON pick_task_details (allocation_id)");
        }
    }

    private record OldDetail(UUID id, String taskNumber, String status, Timestamp createdAt, Timestamp updatedAt) {
    }

    private static void regroupExistingTasks(Connection db) throws Exception {
        Map<UUID, List<OldDetail>> byOrder = new LinkedHashMap<>();
        try (Statement s = db.createStatement();
             ResultSet rs = s.executeQuery("SELECT id, order_id, task_number, status, created_at, updated_at "
                     + "FROM pick_task_details ORDER BY order_id, task_number")) {
            while (rs.next()) {
                byOrder.computeIfAbsent(rs.getObject("order_id", UUID.class), k -> new ArrayList<>())
                        .add(new OldDetail(rs.getObject("id", UUID.class), rs.getString("task_number"),
                                rs.getString("status"), rs.getTimestamp("created_at"), rs.getTimestamp("updated_at")));
            }
        }
        try (PreparedStatement header = db.prepareStatement("INSERT INTO pick_tasks "
                + "(id, task_number, order_id, status, created_at, updated_at, version) VALUES (?, ?, ?, ?, ?, ?, 0)");
             PreparedStatement detail = db.prepareStatement(
                     "UPDATE pick_task_details SET pick_task_id = ?, detail_number = ? WHERE id = ?")) {
            for (Map.Entry<UUID, List<OldDetail>> order : byOrder.entrySet()) {
                List<OldDetail> details = order.getValue();
                UUID taskId = UUID.randomUUID();
                boolean anyOpen = details.stream().anyMatch(d -> "OPEN".equals(d.status()));
                Timestamp created = details.stream().map(OldDetail::createdAt).min(Timestamp::compareTo).orElseThrow();
                Timestamp updated = details.stream().map(OldDetail::updatedAt).max(Timestamp::compareTo).orElseThrow();
                header.setObject(1, taskId);
                header.setString(2, details.getFirst().taskNumber());
                header.setObject(3, order.getKey());
                header.setString(4, anyOpen ? "OPEN" : "CANCELLED");
                header.setTimestamp(5, created);
                header.setTimestamp(6, updated);
                header.executeUpdate();
                for (int i = 0; i < details.size(); i++) {
                    detail.setObject(1, taskId);
                    detail.setInt(2, i + 1);
                    detail.setObject(3, details.get(i).id());
                    detail.executeUpdate();
                }
            }
        }
    }
}
