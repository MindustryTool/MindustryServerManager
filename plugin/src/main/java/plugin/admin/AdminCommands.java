package plugin.admin;

import java.util.List;
import java.util.Set;

import arc.graphics.Color;
import arc.util.Strings;
import mindustry.Vars;
import mindustry.content.Fx;
import mindustry.entities.Effect;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import plugin.annotations.ClientCommand;
import plugin.annotations.Component;
import plugin.annotations.Param;
import plugin.security.ModerationLogService;
import plugin.security.UserBanService;
import plugin.session.Session;
import plugin.utils.Tr;

@Component
public class AdminCommands {

    @ClientCommand(name = "js", description = "Execute JavaScript code")
    public void js(Session session, @Param(name = "code", variadic = true) String[] code) {
        String js = String.join(" ", code);
        String output = Vars.mods.getScripts().runConsole(js);
        session.player.sendMessage(js);
        session.player.sendMessage("> " + (isJsError(output) ? "[#ff341c]" + output : output));
    }

    @ClientCommand(name = "effect", description = "Execute effect")
    public void effect(Session session, @Param(name = "name", required = false) String name) throws Exception {
        if (name == null) {
            var fields = List.of(Fx.class.getDeclaredFields()).stream()
                    .filter(field -> {
                        try {
                            return field.get(null) instanceof Effect;
                        } catch (Exception e) {
                            return false;
                        }
                    })
                    .map(field -> field.getName())
                    .toList();

            session.player.sendMessage("Available effects: " + Strings.join("\n", fields));
            return;
        }

        var field = Fx.class.getField(name);
        var value = field.get(null);

        if (value != null && value instanceof Effect effect) {
            Call.effect(effect, session.player.x, session.player.y, 0, Color.white);
        }
    }

    @ClientCommand(name = "userban", description = "Ban an account by userId", admin = true)
    public void userban(Session session, @Param(name = "userId") String userId, @Param(name = "reason", variadic = true) String[] reasons, UserBanService banService) {
        if (reasons == null || reasons.length == 0) {
            session.player.sendMessage("[scarlet]A reason is required to ban a user.");
            return;
        }
        String reason = String.join(" ", reasons).trim();
        if (reason.isEmpty()) {
            session.player.sendMessage("[scarlet]A reason is required to ban a user.");
            return;
        }

        String actor = (session.player != null && session.player.name != null) ? session.player.name : "ADMIN";
        if (banService.ban(userId, reason, actor)) {
            session.player.sendMessage(Tr.t(session, "security.user_banned_feedback", "id", userId));
        } else {
            session.player.sendMessage("[scarlet]User is already banned or invalid ID.");
        }
    }

    @ClientCommand(name = "userunban", description = "Unban an account by userId", admin = true)
    public void userunban(Session session, @Param(name = "userId") String userId, UserBanService banService) {
        String actor = (session.player != null && session.player.name != null) ? session.player.name : "ADMIN";
        if (banService.unban(userId, actor)) {
            session.player.sendMessage(Tr.t(session, "security.user_unbanned_feedback", "id", userId));
        } else {
            session.player.sendMessage(Tr.t(session, "security.user_not_banned", "id", userId));
        }
    }

    @ClientCommand(name = "userbans", description = "List banned userIds", admin = true)
    public void userbans(Session session, UserBanService banService) {
        Set<String> bans = banService.getBannedUserIds();
        if (bans.isEmpty()) {
            session.player.sendMessage(Tr.t(session, "security.user_bans_empty"));
        } else {
            session.player.sendMessage(Tr.t(session, "security.user_bans_list",
                    "count", bans.size(), "ids", String.join(", ", bans)));
        }
    }

    @ClientCommand(name = "ban", description = "Ban a player with a required reason", admin = true)
    public void ban(
            Session session,
            @Param(name = "type") String type,
            @Param(name = "target") String target,
            @Param(name = "reason", variadic = true) String[] reasons,
            ModerationLogService moderationLogService
    ) {
        if (reasons == null || reasons.length == 0) {
            session.player.sendMessage("[scarlet]A reason is required to ban a player.");
            return;
        }
        String reason = String.join(" ", reasons).trim();
        if (reason.isEmpty()) {
            session.player.sendMessage("[scarlet]A reason is required to ban a player.");
            return;
        }

        String targetUuid = null;
        String targetIp = null;
        String targetName = null;
        String actor = (session.player != null && session.player.name != null) ? session.player.name : "ADMIN";

        if (type.equalsIgnoreCase("id")) {
            targetUuid = target;
            Vars.netServer.admins.banPlayerID(target);
            var info = Vars.netServer.admins.getInfoOptional(target);
            if (info != null) {
                targetName = info.lastName;
                targetIp = info.lastIP;
            }
            session.player.sendMessage("[gold]Banned player ID " + target + " for: " + reason);
        } else if (type.equalsIgnoreCase("name")) {
            Player p = Groups.player.find(pl -> pl.name.equalsIgnoreCase(target) || Strings.stripColors(pl.name).equalsIgnoreCase(target));
            if (p != null) {
                targetUuid = p.uuid();
                targetIp = p.ip();
                targetName = Strings.stripColors(p.name);
                Vars.netServer.admins.banPlayerID(targetUuid);
                Vars.netServer.admins.banPlayerIP(targetIp);
                session.player.sendMessage("[gold]Banned player " + p.name + " for: " + reason);
            } else {
                session.player.sendMessage("[scarlet]No online player by that name found: " + target);
                return;
            }
        } else if (type.equalsIgnoreCase("ip")) {
            targetIp = target;
            Vars.netServer.admins.banPlayerIP(target);
            var info = Vars.netServer.admins.findByIP(target);
            if (info != null) {
                targetUuid = info.id;
                targetName = info.lastName;
            }
            session.player.sendMessage("[gold]Banned IP " + target + " for: " + reason);
        } else {
            session.player.sendMessage("[scarlet]Invalid ban type. Use 'id', 'name', or 'ip'.");
            return;
        }

        for (Player p : Groups.player) {
            if (Vars.netServer.admins.isIDBanned(p.uuid()) || Vars.netServer.admins.isIPBanned(p.ip())) {
                p.kick(reason);
            }
        }

        moderationLogService.logBan("BAN", null, targetUuid, targetIp, targetName, reason, actor);
    }

    private boolean isJsError(String output) {
        try {
            String errorName = output.substring(0, output.indexOf(' ') - 1);
            Class.forName("org.mozilla.javascript." + errorName);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
