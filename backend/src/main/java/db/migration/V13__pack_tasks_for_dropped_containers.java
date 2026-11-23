package db.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * Gives every container dropped at a pack station before packing existed its packing task.
 * Written in Java so it works the same on H2 and PostgreSQL.
 */
public class V13__pack_tasks_for_dropped_containers extends BaseJavaMigration {

    private record Dropped(UUID pickTaskId, UUID containerId, UUID stationId, Timestamp completedAt) {
    }

    @Override
    public void migrate(Context context) throws Exception {
        Connection db = context.getConnection();
        List<Dropped> dropped = new ArrayList<>();
        try (Statement s = db.createStatement();
             ResultSet rs = s.executeQuery("SELECT id, to_container_id, drop_location_id, completed_at FROM pick_tasks "
                     + "WHERE status = 'COMPLETED' AND to_container_id IS NOT NULL AND drop_location_id IS NOT NULL "
                     + "ORDER BY completed_at, task_number")) {
            while (rs.next()) {
                dropped.add(new Dropped(rs.getObject("id", UUID.class), rs.getObject("to_container_id", UUID.class),
                        rs.getObject("drop_location_id", UUID.class), rs.getTimestamp("completed_at")));
            }
        }
        for (Dropped d : dropped) {
            addTask(db, d);
        }
    }

    private static void addTask(Connection db, Dropped d) throws Exception {
        UUID taskId = UUID.randomUUID();
        long number;
        try (Statement s = db.createStatement(); ResultSet rs = s.executeQuery("SELECT nextval('pack_task_number_seq')")) {
            rs.next();
            number = rs.getLong(1);
        }
        try (PreparedStatement task = db.prepareStatement("INSERT INTO pack_tasks (id, task_number, pick_task_id, "
                + "container_id, station_id, status, created_at, updated_at, version) VALUES (?, ?, ?, ?, ?, 'OPEN', ?, ?, 0)")) {
            task.setObject(1, taskId);
            task.setString(2, "PK%06d".formatted(number));
            task.setObject(3, d.pickTaskId());
            task.setObject(4, d.containerId());
            task.setObject(5, d.stationId());
            task.setTimestamp(6, d.completedAt());
            task.setTimestamp(7, d.completedAt());
            task.executeUpdate();
        }
        try (PreparedStatement picks = db.prepareStatement("SELECT d.* FROM pick_task_details d "
                + "JOIN outbound_orders o ON o.id = d.order_id JOIN order_lines l ON l.id = d.order_line_id "
                + "WHERE d.pick_task_id = ? AND d.picked_quantity > 0 AND d.picked_allocation_id IS NOT NULL "
                + "ORDER BY o.order_number, l.line_number, d.detail_number");
             PreparedStatement detail = db.prepareStatement("INSERT INTO pack_task_details (id, pack_task_id, detail_number, "
                     + "order_id, order_line_id, item_id, pick_task_detail_id, allocation_id, lot_number, serial_number, "
                     + "quantity, version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)")) {
            picks.setObject(1, d.pickTaskId());
            int n = 0;
            try (ResultSet rs = picks.executeQuery()) {
                while (rs.next()) {
                    detail.setObject(1, UUID.randomUUID());
                    detail.setObject(2, taskId);
                    detail.setInt(3, ++n);
                    detail.setObject(4, rs.getObject("order_id", UUID.class));
                    detail.setObject(5, rs.getObject("order_line_id", UUID.class));
                    detail.setObject(6, rs.getObject("item_id", UUID.class));
                    detail.setObject(7, rs.getObject("id", UUID.class));
                    detail.setObject(8, rs.getObject("picked_allocation_id", UUID.class));
                    detail.setString(9, rs.getString("lot_number"));
                    detail.setString(10, rs.getString("serial_number"));
                    detail.setInt(11, rs.getInt("picked_quantity"));
                    detail.addBatch();
                }
            }
            detail.executeBatch();
        }
    }
}
