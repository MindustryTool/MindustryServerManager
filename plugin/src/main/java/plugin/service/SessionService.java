package plugin.service;

import java.util.function.Function;

import arc.util.Log;
import dto.LoginDto;
import mindustry.Vars;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.net.Administration.PlayerInfo;
import plugin.Tasks;
import plugin.annotations.Component;
import plugin.repository.SessionRepository;
import plugin.type.Session;
import plugin.type.SessionData;
import plugin.utils.ExpUtils;
import plugin.utils.SessionUtils;
import plugin.utils.Utils;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class SessionService {
    private final SessionRepository sessionRepository;

    public Function<Session, Integer> getLevel = session -> {
        SessionData data = session.getData();

        // Using existing logic for compatibility: calculating exp based on session play
        // time
        long currentSessionTime = session.sessionPlayTime();
        long calculatedExp = ExpUtils.getTotalExp(data, currentSessionTime);

        int level = ExpUtils.levelFromTotalExp(calculatedExp);

        return level;
    };

    public void update(Session session) {
        int level = getLevel.apply(session);

        if (level != session.currentLevel) {
            if (session.currentLevel != 0) {
                int oldLevel = session.currentLevel;
                int newLevel = level;

                if (level > session.currentLevel) {
                    Tasks.io("Update level", () -> {
                        Utils.forEachPlayerLocale((locale, players) -> {
                            String message = SessionUtils.getLevelUpMessage(locale, oldLevel, newLevel);
                            players.forEach(p -> p.sendMessage(session.player.name + message));
                        });
                    });
                }
            }

            session.currentLevel = level;
            session.player.name(SessionUtils.getPlayerName(session));
        }

        sessionRepository.markDirty(session.player.uuid());
    }

    public void setLogin(Session session, LoginDto login) {
        PlayerInfo target = Vars.netServer.admins.getInfoOptional(session.player.uuid());

        if (target != null) {
            Player playert = Groups.player.find(p -> p.getInfo() == target);

            if (login.getIsAdmin()) {
                Vars.netServer.admins.adminPlayer(target.id, playert == null ? target.adminUsid : playert.usid());
            } else {
                Vars.netServer.admins.unAdminPlayer(target.id);
            }
        } else {
            session.player.admin = false;
            Log.info("Player @ is no longer an admin", session.player.name);
        }

        Log.info(login);

        session.login = login;
        session.player.admin = false;
        session.player.name(SessionUtils.getPlayerName(session));

        if (login.getIsAdmin()) {
            session.player.sendMessage(I18n.t(session, "[accent]", "@Use /admin to toogle admin"));
        }
    }
}
