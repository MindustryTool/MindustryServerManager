package plugin.type;

import java.time.Instant;
import java.util.Locale;

import mindustry.gen.Player;

public class Session {
    public final Locale locale;
    public final Player player;
    public final Long joinedAt = Instant.now().toEpochMilli();
    private final SessionData data;

    public boolean votedVNW = false;
    public boolean votedGrief = false;
    public int currentLevel = 0;

    public Session(Player player, SessionData data) {
        this.player = player;
        this.data = data;
        this.locale = Locale.forLanguageTag(player.locale().replace("_", "-"));
    }

    public void reset() {
        player.name(data.name);
    }

    public SessionData getData() {
        return data;
    }

    public long sessionPlayTime() {
        return Instant.now().toEpochMilli() - joinedAt;
    }

    @Override
    public String toString() {
        return "Session<" + player.uuid() + ":" + player.name + ">";
    }
}
