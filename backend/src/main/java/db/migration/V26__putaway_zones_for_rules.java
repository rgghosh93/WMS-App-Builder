package db.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * Put-away rules named the zone in a location's address; now they name a put-away zone. Each such
 * address zone becomes a put-away zone with the same locations, so the rules do what they did.
 * Written in Java so it works the same on H2 and PostgreSQL.
 */
public class V26__putaway_zones_for_rules extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection db = context.getConnection();
        List<String[]> named = new ArrayList<>();
        try (Statement s = db.createStatement(); ResultSet rs = s.executeQuery(
                "SELECT DISTINCT area_code, zone_code FROM putaway_rules WHERE zone_code IS NOT NULL ORDER BY area_code, zone_code")) {
            while (rs.next()) {
                named.add(new String[] {rs.getString(1), rs.getString(2)});
            }
        }
        for (String[] zone : named) {
            UUID id = UUID.randomUUID();
            try (PreparedStatement insert = db.prepareStatement("INSERT INTO zones (id, zone_function, code, name, status, allocatable, version) "
                    + "VALUES (?, 'PUTAWAY', ?, ?, 'ACTIVE', TRUE, 0)")) {
                insert.setObject(1, id);
                insert.setString(2, zone[0] + "-" + zone[1]);
                insert.setString(3, "Zone " + zone[1] + " of " + zone[0]);
                insert.executeUpdate();
            }
            List<UUID> locations = new ArrayList<>();
            try (PreparedStatement find = db.prepareStatement(
                    "SELECT id FROM locations WHERE location_type = 'STORAGE' AND area_code = ? AND zone_code = ?")) {
                find.setString(1, zone[0]);
                find.setString(2, zone[1]);
                try (ResultSet rs = find.executeQuery()) {
                    while (rs.next()) {
                        locations.add(rs.getObject(1, UUID.class));
                    }
                }
            }
            try (PreparedStatement insert = db.prepareStatement(
                    "INSERT INTO zone_locations (id, zone_id, zone_function, location_id) VALUES (?, ?, 'PUTAWAY', ?)")) {
                for (UUID location : locations) {
                    insert.setObject(1, UUID.randomUUID());
                    insert.setObject(2, id);
                    insert.setObject(3, location);
                    insert.executeUpdate();
                }
            }
            try (PreparedStatement update = db.prepareStatement("UPDATE putaway_rules SET zone_id = ? WHERE area_code = ? AND zone_code = ?")) {
                update.setObject(1, id);
                update.setString(2, zone[0]);
                update.setString(3, zone[1]);
                update.executeUpdate();
            }
        }
        try (Statement s = db.createStatement()) {
            s.execute("ALTER TABLE putaway_rules DROP COLUMN area_code");
            s.execute("ALTER TABLE putaway_rules DROP COLUMN zone_code");
        }
    }
}
