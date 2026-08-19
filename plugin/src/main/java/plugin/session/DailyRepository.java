package plugin.session;

import java.util.Optional;

import arc.util.Log;
import plugin.annotations.Component;
import plugin.annotations.Init;
import plugin.database.Database;

@Component
public class DailyRepository {
    private final Database database;

    public DailyRepository(Database database) {
        this.database = database;
    }

    @Init
    public void init() {
        createTableIfNotExists();
    }

    public Optional<String> getLastLogin(String uuid) {
        var sql = "SELECT last_login_date FROM player_logins WHERE uuid = ?";

        try {
            return database.prepare(sql, ps -> {
                ps.setString(1, uuid);

                try (var rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(rs.getString(1));
                    }
                    return Optional.empty();
                }
            });
        } catch (Exception e) {
            Log.err("Error while reading last login for uuid: @", uuid, e);
            return Optional.empty();
        }
    }

    public void setLastLogin(String uuid, String date) {
        var sql = "INSERT INTO player_logins(uuid, last_login_date) VALUES(?, ?) ON CONFLICT(uuid) DO UPDATE SET last_login_date = excluded.last_login_date";

        try {
            database.prepare(sql, ps -> {
                ps.setString(1, uuid);
                ps.setString(2, date);
                ps.executeUpdate();
            });
        } catch (Exception e) {
            Log.err("Error while saving last login for uuid: @", uuid, e);
        }
    }

    private void createTableIfNotExists() {
        try {
            var sql = "CREATE TABLE IF NOT EXISTS player_logins (uuid TEXT PRIMARY KEY, last_login_date TEXT NOT NULL)";

            database.statement(statement -> {
                statement.executeUpdate(sql);
            });
        } catch (Exception e) {
            Log.err("Failed to create player_logins table: @", e);
        }
    }
}
