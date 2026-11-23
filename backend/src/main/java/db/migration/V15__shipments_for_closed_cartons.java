package db.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * Puts cartons closed before shipping existed on a shipment, one per order.
 * Written in Java so it works the same on H2 and PostgreSQL.
 */
public class V15__shipments_for_closed_cartons extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection db = context.getConnection();
        Map<UUID, Timestamp> firstClosed = new LinkedHashMap<>();
        try (Statement s = db.createStatement();
             ResultSet rs = s.executeQuery("SELECT order_id, MIN(closed_at) AS first_closed FROM pack_cartons "
                     + "WHERE status = 'CLOSED' GROUP BY order_id ORDER BY MIN(closed_at)")) {
            while (rs.next()) {
                firstClosed.put(rs.getObject("order_id", UUID.class), rs.getTimestamp("first_closed"));
            }
        }
        for (Map.Entry<UUID, Timestamp> order : firstClosed.entrySet()) {
            long number;
            try (Statement s = db.createStatement(); ResultSet rs = s.executeQuery("SELECT nextval('shipment_number_seq')")) {
                rs.next();
                number = rs.getLong(1);
            }
            UUID shipmentId = UUID.randomUUID();
            try (PreparedStatement insert = db.prepareStatement("INSERT INTO shipments (id, shipment_number, order_id, "
                    + "status, created_at, updated_at, version) VALUES (?, ?, ?, 'OPEN', ?, ?, 0)")) {
                insert.setObject(1, shipmentId);
                insert.setString(2, "SH%06d".formatted(number));
                insert.setObject(3, order.getKey());
                insert.setTimestamp(4, order.getValue());
                insert.setTimestamp(5, order.getValue());
                insert.executeUpdate();
            }
            try (PreparedStatement update = db.prepareStatement(
                    "UPDATE pack_cartons SET shipment_id = ? WHERE order_id = ? AND status = 'CLOSED'")) {
                update.setObject(1, shipmentId);
                update.setObject(2, order.getKey());
                update.executeUpdate();
            }
        }
    }
}
