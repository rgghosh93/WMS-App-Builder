package db.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.UUID;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * Starts the stock history with what is on hand, so the history always adds up to the stock.
 * Written in Java so it works the same on H2 and PostgreSQL.
 */
public class V24__opening_stock extends BaseJavaMigration {

    private static final String STOCK = """
            with recursive place (id, lpn, location_id, container_id) as (
                select i.id, c.lpn_id, i.location_id, i.container_id from inventory i left join containers c on c.id = i.container_id
                union all
                select p.id, p.lpn, c.current_location_id, c.parent_container_id from place p join containers c on c.id = p.container_id)
            select i.id, i.item_id, t.item_id as item_code, i.lot_number, i.serial_number, i.expiry_date, i.quantity, i.status,
                (select max(p.lpn) from place p where p.id = i.id) as lpn,
                (select max(l.location_id) from place p join locations l on l.id = p.location_id where p.id = i.id) as location
            from inventory i join items t on t.id = i.item_id order by t.item_id, i.received_at""";

    @Override
    public void migrate(Context context) throws Exception {
        Connection db = context.getConnection();
        Timestamp now = Timestamp.from(Instant.now());
        try (Statement s = db.createStatement();
             ResultSet rs = s.executeQuery(STOCK);
             PreparedStatement insert = db.prepareStatement("INSERT INTO inventory_transactions (id, number, occurred_at, type, item_id, "
                     + "item_code, lot_number, serial_number, expiry_date, quantity, to_location, to_lpn, to_status, reason, user_name) "
                     + "VALUES (?, ?, ?, 'OPENING', ?, ?, ?, ?, ?, ?, ?, ?, ?, 'On hand when the history began', 'System')")) {
            while (rs.next()) {
                insert.setObject(1, UUID.randomUUID());
                insert.setLong(2, next(db));
                insert.setTimestamp(3, now);
                insert.setObject(4, rs.getObject("item_id", UUID.class));
                insert.setString(5, rs.getString("item_code"));
                insert.setString(6, rs.getString("lot_number"));
                insert.setString(7, rs.getString("serial_number"));
                if (rs.getDate("expiry_date") == null) {
                    insert.setNull(8, Types.DATE);
                } else {
                    insert.setDate(8, rs.getDate("expiry_date"));
                }
                insert.setInt(9, rs.getInt("quantity"));
                insert.setString(10, rs.getString("location"));
                insert.setString(11, rs.getString("lpn"));
                insert.setString(12, rs.getString("status"));
                insert.executeUpdate();
            }
        }
    }

    private static long next(Connection db) throws Exception {
        try (Statement s = db.createStatement(); ResultSet rs = s.executeQuery("SELECT nextval('inventory_transaction_seq')")) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
