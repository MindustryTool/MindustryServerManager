package plugin.service;

import arc.Core;
import arc.util.Log;
import mindustry.Vars;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.net.Administration.PlayerInfo;
import mindustry.type.UnitType;
import plugin.Tasks;
import plugin.annotations.Component;
import plugin.repository.SessionRepository;
import plugin.type.Session;
import plugin.type.SessionData;
import plugin.utils.ExpUtils;
import plugin.utils.Utils;
import plugin.view.SessionView;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class SessionService {
    private final SessionRepository sessionRepository;

    public void addKill(Session session, UnitType unit, int amount) {
        if (amount <= 0)
            return;

        SessionData data = session.getData();
        long totalKills;

        // Synchronize on data to ensure thread safety for mutations
        synchronized (data) {
            totalKills = data.kills.getOrDefault(unit.id, 0L) + amount;
            data.kills.put(unit.id, totalKills);
        }

        // Explicitly mark dirty for persistence
        sessionRepository.markDirty(session.player.uuid());

        checkKillMilestone(session, unit, totalKills);
    }

    private void checkKillMilestone(Session session, UnitType unit, long totalKills) {
        long base = 1;
        while (totalKills >= base * 10) {
            base *= 10;
        }

        long print = (totalKills / base) * base;

        if (totalKills != print || totalKills < 10) {
            return;
        }

        long exp = ExpUtils.unitHealthToExp(totalKills * unit.health);

        Utils.forEachPlayerLocale((locale, players) -> {
            String message = SessionView.getKillMessage(locale, session.player.name, print, unit, exp);
            for (var p : players) {
                p.sendMessage(message);
            }
        });
    }

    public void update(Session session) {
        SessionData data = session.getData();

        // Using existing logic for compatibility: calculating exp based on session play
        // time
        long currentSessionTime = session.sessionPlayTime();
        long calculatedExp = ExpUtils.getTotalExp(data, currentSessionTime);

        int level = ExpUtils.levelFromTotalExp(calculatedExp);

        if (level != session.currentLevel) {
            if (session.currentLevel != 0) {
                int oldLevel = session.currentLevel;
                int newLevel = level;

                Tasks.io("Update level", () -> {
                    Utils.forEachPlayerLocale((locale, players) -> {
                        String message = SessionView.getLevelUpMessage(locale, oldLevel, newLevel);
                        players.forEach(p -> p.sendMessage(session.player.name + message));
                    });
                });
            }

            session.currentLevel = level;

            // Update player name with new level
            // Ideally UI updates should be separate, but name update is part of the state
            // sync here
            Core.app.post(() -> {
                session.player.name(SessionView.getPlayerName(session.player, data, level));
            });
        }

        sessionRepository.markDirty(session.player.uuid());
    }

    public void setAdmin(Session session, boolean isAdmin) {

        if (isAdmin) {
            Utils.forEachPlayerLocale((locale, players) -> {
                String msg = SessionView.getAdminLoginMessage(locale, session.player.name);
                for (var p : players) {
                    p.sendMessage(msg);
                }
            });
        }

        PlayerInfo target = Vars.netServer.admins.getInfoOptional(session.player.uuid());

        if (target != null) {
            Player playert = Groups.player.find(p -> p.getInfo() == target);

            if (isAdmin) {
                Vars.netServer.admins.adminPlayer(target.id, playert == null ? target.adminUsid : playert.usid());
            } else {
                Vars.netServer.admins.unAdminPlayer(target.id);
            }
        } else {
            Core.app.post(() -> {
                session.player.admin = false;
                Log.info("Player @ is no longer an admin", session.player.name);
            });
        }

        Core.app.post(() -> {
            session.player.admin = isAdmin;
            session.player.name(SessionView.getPlayerName(session.player, session.getData(), session.currentLevel));
        });
    }
}
