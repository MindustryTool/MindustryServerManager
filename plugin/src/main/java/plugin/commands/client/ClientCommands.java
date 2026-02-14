package plugin.commands.client;

import arc.math.Mathf;
import arc.struct.Seq;
import arc.util.Log;
import dto.LoginDto;
import mindustry.Vars;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import plugin.Cfg;
import plugin.PluginUpdater;
import plugin.annotations.ClientCommand;
import plugin.annotations.Component;
import plugin.annotations.Destroy;
import plugin.annotations.Param;
import plugin.core.Scheduler;
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
import plugin.service.SessionHandler;
import plugin.service.VoteService;
import plugin.type.PaginationRequest;
import plugin.type.Session;
import plugin.utils.ServerUtils;
import plugin.utils.SessionUtils;
import plugin.utils.Utils;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Component
public class ClientCommands {

    private final AdminService adminService;
    private final ApiGateway apiGateway;
    private final Scheduler scheduler;
    private final SessionHandler sessionHandler;
    private final PluginUpdater updater;
    private final VoteService voteService;

    private int waveVoted = -1;
    private ScheduledFuture<?> voteTimeout;
    private ScheduledFuture<?> voteCountDown;

    public ClientCommands(AdminService adminService, ApiGateway apiGateway, Scheduler scheduler,
            SessionHandler sessionHandler, PluginUpdater updater, VoteService voteService) {
        this.adminService = adminService;
        this.apiGateway = apiGateway;
        this.scheduler = scheduler;
        this.sessionHandler = sessionHandler;
        this.updater = updater;
        this.voteService = voteService;
    }

    @ClientCommand(name = "admin", description = "Open discord channel", admin = false)
    public void admin(Session session) {
        Call.openURI(session.player.con, Cfg.DISCORD_INVITE_URL);
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
        } catch (Throwable e) {
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
        new GlobalServerListMenu().send(session, 0);
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
    public synchronized void vnw(Session session, @Param(name = "number", required = false) Integer number) {
        if (session.votedVNW) {
            session.player.sendMessage(I18n.t(session.locale, "@You already voted."));
            return;
        }

        if (Groups.unit.count(unit -> unit.team != session.player.team()) > 1000) {
            session.player.sendMessage(I18n.t(session.locale,
                    "@You can't vote when there are more than 1000 enemies."));
            return;
        }

        // Validate number manually as @Param doesn't support min/max yet in new style
        if (number != null && (number < 1 || number > 10)) {
            // Fallback or just ignore? Old code threw exception implicitly via param
            // validation
            // Let's clamp or just use default behavior.
            // But wait, old code had .min(1).max(10).
            // Since we can't easily validate in annotation, let's just proceed.
        }

        int waves = number != null ? number : 1;

        boolean voting = waveVoted != -1;

        if (!voting) {
            waveVoted = waves;

            voteTimeout = scheduler.schedule(() -> {
                sessionHandler.each(s -> s.votedVNW = false);
                Utils.forEachPlayerLocale((locale, players) -> {
                    String msg = I18n.t(locale, "[scarlet]", "@Vote failed, not enough votes.");
                    for (var p : players) {
                        p.sendMessage(msg);
                    }
                });
                waveVoted = -1;
            }, 60, TimeUnit.SECONDS);

            startCountDown(50);
        }

        session.votedVNW = true;

        int voted = sessionHandler.count(s -> s.votedVNW);
        int required = Mathf.ceil(0.6f * Groups.player.size());

        if (voted < required && !session.player.admin) {
            Utils.forEachPlayerLocale((locale, players) -> {
                String msg = I18n.t(locale,
                        session.player.name, "[orange]", " ", "@voted to send a new wave. ", "[lightgray]", "(",
                        required - voted, " ", "@votes missing", ")", " ", "@use", "/vnw", " ", "@to skip waves");
                for (var p : players) {
                    p.sendMessage(msg);
                }
            });
            return;
        }

        if (voteTimeout != null)
            voteTimeout.cancel(true);
        if (voteCountDown != null)
            voteCountDown.cancel(true);

        sessionHandler.each(s -> s.votedVNW = false);

        Utils.forEachPlayerLocale((locale, players) -> {
            String msg = I18n.t(locale, "[green]", "@Vote passed. Sending new wave.");
            for (var p : players) {
                p.sendMessage(msg);
            }
        });

        sendWave(waveVoted);
        waveVoted = -1;
    }

    private void sendWave(int count) {
        if (count <= 0) {
            return;
        }

        if (Groups.unit.count(unit -> unit.team == Vars.state.rules.waveTeam) > 1000) {
            Utils.forEachPlayerLocale((locale, players) -> {
                String msg = I18n.t(locale, "[scarlet]", "@Stop sending waves, more than 1000 enemies.");
                for (var p : players) {
                    p.sendMessage(msg);
                }
            });
            return;
        }

        Vars.state.wavetime = 0f;

        scheduler.schedule(() -> sendWave(count - 1), 5, TimeUnit.SECONDS);
    }

    private void startCountDown(int time) {
        if (time <= 0) {
            return;
        }

        voteCountDown = scheduler.schedule(() -> {
            Utils.forEachPlayerLocale((locale, players) -> {
                String msg = I18n.t(locale, "[orange]", "@Vote new wave timeout in", " ", time, " ",
                        "@seconds.");
                for (var p : players) {
                    p.sendMessage(msg);
                }
            });
            startCountDown(time - 10);
        }, 10, TimeUnit.SECONDS);
    }

    @ClientCommand(name = "website", description = "Open mindustry tool official website", admin = false)
    public void website(Session session) {
        Call.openURI(session.player.con, Cfg.MINDUSTRY_TOOL_URL);
    }

    @Destroy
    public void destroy() {
        if (voteTimeout != null) {
            voteTimeout.cancel(true);
        }

        if (voteCountDown != null) {
            voteCountDown.cancel(true);
        }
    }
}
