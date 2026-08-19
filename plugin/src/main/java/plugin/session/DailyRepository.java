package plugin.session;

import java.util.Optional;

import arc.util.Log;
import plugin.annotations.Component;
import plugin.annotations.Init;
import plugin.database.Database;
import plugin.database.schema.PlayerLogins;

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
        try {
            var row = database.db().select(PlayerLogins.LAST_LOGIN_DATE).from(PlayerLogins.TABLE)
                    .where(PlayerLogins.UUID.eq(uuid))
                    .fetchOne()
                    .orElse(null);

            if (row == null) {
                return Optional.empty();
            }

            return Optional.ofNullable(row.get(PlayerLogins.LAST_LOGIN_DATE));
        } catch (Exception e) {
            Log.err("Error while reading last login for uuid: @", uuid, e);
            return Optional.empty();
        }
    }

    public void setLastLogin(String uuid, String date) {
        try {
            database.db().insert(PlayerLogins.TABLE)
                    .set(PlayerLogins.UUID, uuid)
                    .set(PlayerLogins.LAST_LOGIN_DATE, date)
                    .onConflictDoUpdate(PlayerLogins.UUID, PlayerLogins.LAST_LOGIN_DATE)
                    .execute();
        } catch (Exception e) {
            Log.err("Error while saving last login for uuid: @", uuid, e);
        }
    }

    private void createTableIfNotExists() {
        try {
            database.db().createTableIfNotExists(PlayerLogins.TABLE);
        } catch (Exception e) {
            Log.err("Failed to create player_logins table: @", e);
        }
    }
}
