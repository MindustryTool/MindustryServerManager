package plugin.type;

import java.time.Instant;
import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonIgnore;

import mindustry.game.Team;
import mindustry.gen.Player;

public class Session {
    public final int playerId;
    public final String playerUuid;
    public final Long joinedAt = Instant.now().toEpochMilli();
    public final Locale locale;

    @JsonIgnore
    public Team spectate = null;

    public boolean votedVNW = false;

    public Session(Player p) {
        this.playerId = p.id();
        this.playerUuid = p.uuid();
        this.locale = Locale.forLanguageTag(p.locale().replace('_', '-'));
    }

    public boolean spectate() {
        return this.spectate != null;
    }
}
