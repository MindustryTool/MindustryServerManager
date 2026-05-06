package plugin.commands.client;

import arc.struct.Seq;
import arc.util.Log;
import dto.LoginDto;
import lombok.RequiredArgsConstructor;
import mindustry.Vars;
import mindustry.gen.Call;
import mindustry.maps.Map;
import plugin.Cfg;
import plugin.PluginUpdater;
import plugin.annotations.ClientCommand;
import plugin.annotations.Component;
import plugin.annotations.Param;
import plugin.menus.GlobalServerListMenu;
import plugin.menus.GriefMenu;
import plugin.menus.PlayerInfoMenu;
import plugin.menus.RtvMenu;
import plugin.menus.ServerListMenu;
import plugin.menus.TrailMenu;
import plugin.service.AdminService;
import plugin.service.ApiGateway;
import plugin.service.I18n;
import plugin.service.MapRating;
import plugin.service.VoteNewWaveService;
import plugin.service.VoteService;
import plugin.type.PaginationRequest;
import plugin.type.Session;
import plugin.utils.ServerUtils;
import plugin.utils.SessionUtils;

@Component
@RequiredArgsConstructor
public class ClientCommands {

    private final AdminService adminService;
    private final ApiGateway apiGateway;
    private final PluginUpdater updater;
    private final VoteService voteService;
    private final VoteNewWaveService voteNewWaveService;

    @ClientCommand(name = "admin", description = "Toogle admin permisison", admin = false)
    public void admin(Session session) {
        if (session.isAdmin()) {
            session.player.admin = !session.player.admin;
        } else {
            session.player.sendMessage(I18n.t(session, "@You are not an admin"));
        }
    }

    @ClientCommand(name = "discord", description = "Open discord channel", admin = false)
    public void discord(Session session) {
        Call.openURI(session.player.con, Cfg.DISCORD_INVITE_URL);
    }

    @ClientCommand(name = "grief", description = "Report a player", admin = false)
    public void grief(Session session) {
        if (adminService.isGriefVoting()) {
            adminService.voteGrief(session);
            return;
        }

        new GriefMenu().send(session, null);
    }

    @ClientCommand(name = "hub", description = "Go to hub", admin = false)
    public void hub(Session session) {
        var servers = Seq.with(apiGateway.getServers(new PaginationRequest().setPage(0).setSize(20)));

        var hub = servers.find(server -> server.getIsHub());

        if (hub == null) {
            session.player.sendMessage(I18n.t(session, "@Hub not found"));
            return;
        }

        ServerUtils.redirect(session.player, hub);
    }

    @ClientCommand(name = "js", description = "Execute JavaScript code")
    public void js(Session session, @Param(name = "code", variadic = true) String[] code) {
        String js = String.join(" ", code);
        String output = Vars.mods.getScripts().runConsole(js);
        session.player.sendMessage(js);
        session.player.sendMessage("> " + (isJsError(output) ? "[#ff341c]" + output : output));
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

    @ClientCommand(name = "login", description = "Login", admin = false)
    public void login(Session session) {
        try {
            LoginDto login = apiGateway.login(session.player);

            var loginLink = login.getLoginLink();

            if (loginLink != null && !loginLink.isEmpty()) {
                Call.openURI(session.player.con, loginLink);
            } else {
                session.player.sendMessage(I18n.t(session.locale,
                        "@Already logged in"));
            }
        } catch (Exception e) {
            Log.err("Failed to login", e);
        }
    }

    @ClientCommand(name = "map", description = "Display current map info", admin = false)
    public void map(Session session) {
        if (Vars.state.map == null) {
            session.player.sendMessage(I18n.t(session.locale,
                    "@Map is not loaded"));
            return;
        }

        session.player.sendMessage(MapRating.getDisplayString(Vars.state.map));

    }

    @ClientCommand(name = "maps", description = "List map", admin = false)
    public void maps(Session session, @Param(name = "page", required = false) Integer page) {
        int pageSize = 5;
        if (page != null) {
            page = 0;
        }

        int start = page * pageSize;
        int end = start + pageSize;
        int maxSize = Vars.maps.customMaps().size;

        if (start >= maxSize) {
            session.player.sendMessage(I18n.t(session.locale, "@No more maps"));
            return;
        }

        if (end > maxSize) {
            end = maxSize;
        }

        StringBuilder sb = new StringBuilder("Id\n");
        for (int i = start; i < end; i++) {
            Map map = Vars.maps.customMaps().get(i);
            sb.append(i)
                    .append(" ")
                    .append(map.name())
                    .append("")
                    .append(MapRating.getAvgString(map))
                    .append("\n");
        }
        session.player.sendMessage(sb.toString());
    }

    @ClientCommand(name = "me", description = "Display your info", admin = false)
    public void me(Session session) {
        session.player.sendMessage(SessionUtils.getInfoString(session, session.getData()));
    }

    @ClientCommand(name = "pinfo", description = "Display player info", admin = false)
    public void pinfo(Session session) {
        new PlayerInfoMenu().send(session, session);
    }

    @ClientCommand(name = "redirect", description = "Redirect all player to server")
    public void redirect(Session session) {
        new GlobalServerListMenu(server -> ServerUtils.redirectAll(server)).send(session, 0);
    }

    @ClientCommand(name = "restart", description = "Restart the server")
    public void restart(Session session) {
        Call.sendMessage("[cyan]Server scheduled for a restart.");
        updater.scheduleRestart();
    }

    @ClientCommand(name = "rtv", description = "Vote to change map", admin = false)
    public void rtv(Session session, @Param(name = "yes", required = false) String yes) {
        if (yes != null && yes.equalsIgnoreCase("yes")) {
            if (voteService.lastMap == null) {
                session.player.sendMessage(I18n.t(session.locale, "@No map is currently being voted on."));
            } else {
                voteService.handleVote(session.player);
            }
        } else {
            new RtvMenu().send(session, 0);
        }
    }

    @ClientCommand(name = "servers", description = "Display available servers", admin = false)
    public void servers(Session session) {
        new ServerListMenu().send(session, 0);
    }

    @ClientCommand(name = "submitmap", description = "Open discord channel", admin = false)
    public void submitmap(Session session) {
        Call.openURI(session.player.con, Cfg.DISCORD_INVITE_URL);
    }

    @ClientCommand(name = "trail", description = "Toggle trail", admin = false)
    public void trail(Session session) {
        new TrailMenu().send(session, 0);
    }

    @ClientCommand(name = "vnw", description = "Vote for sending a new Wave", admin = false)
    public void vnw(Session session, @Param(name = "number", required = false) Integer number) {
        voteNewWaveService.vote(session, number);
    }

    @ClientCommand(name = "website", description = "Open mindustry tool official website", admin = false)
    public void website(Session session) {
        Call.openURI(session.player.con, Cfg.MINDUSTRY_TOOL_URL);
    }
}
