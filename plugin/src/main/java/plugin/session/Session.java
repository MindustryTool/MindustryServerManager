package plugin.session;

import java.time.Instant;
import java.util.Locale;

import dto.LoginDto;
import mindustry.gen.Player;

public class Session {
    public final Locale locale;
    public final Player player;
    public final Long joinedAt = Instant.now().toEpochMilli();
    private final SessionData data;

    public LoginDto login;
    public boolean votedVNW = false;
    public boolean votedGrief = false;
    
    public int currentLevel = 0;
    public float expGainBonus = 0;

    public static enum AfkState {
        ACTIVE,
        POTENTIAL_AFK,
        AFK
    }

    public AfkState afkState = AfkState.ACTIVE;
    public double lastX = 0;
    public double lastY = 0;
    public double lastClickX = 0;
    public double lastClickY = 0;
    public Instant lastPotentialAfkTime = Instant.now();
    public Instant lastClickTime = Instant.now();

    public Session(Player player, SessionData data) {
        this.player = player;
        this.data = data;
        this.locale = Locale.forLanguageTag(player.locale().replace("_", "-"));
    }

    public boolean isAfk() {
        return afkState == AfkState.AFK;
    }

    public boolean isAdmin() {
        return login != null && login.getIsAdmin();
    }

    public boolean isLoggedIn() {
        return login != null && login.getLoginLink() == null;
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
