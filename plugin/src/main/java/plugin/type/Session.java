package plugin.type;

import java.time.Instant;
import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonIgnore;

import mindustry.gen.Player;

public class Session {
    public final int playerId;
    public final String playerUuid;
    public final Locale locale;
    public final Long joinedAt = Instant.now().toEpochMilli();

    @JsonIgnore
    public mindustry.game.Team spectate = null;

    public String tag = "", noColorTag = "", rainbowedName = "";
    public int hue = 0;
    public boolean votedVNW = false, //
            votedRTV = false, //
            rainbowed = false, //
            hasEffect = false, //
            isMuted = false, //
            inGodmode = false, //
            isCreator;

    public Session(Player p) {
        this.playerId = p.id();
        this.playerUuid = p.uuid();
        this.locale = Locale.forLanguageTag(p.locale().replace('_', '-'));
    }

    public boolean spectate() {
        return this.spectate != null;
    }

}
