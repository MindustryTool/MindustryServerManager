package plugin.gamemode.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import arc.Core;
import arc.Settings;
import arc.files.Fi;
import arc.func.Cons;
import mindustry.Vars;
import mindustry.game.Team;
import mindustry.gen.Player;
import mindustry.net.Host;
import mindustry.net.Net;
import mindustry.net.NetConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import plugin.gamemode.Gamemode;
import plugin.gamemode.GamemodeCondition;
import plugin.menus.MenuContext;
import plugin.session.Session;
import plugin.session.SessionData;

public class SandboxTeamTest {

    @TempDir
    static Path settingsDir;

    @BeforeAll
    static void initSettings() {
        Core.settings = new Settings();
        Core.settings.setDataDirectory(new Fi(settingsDir.toFile()));

        Vars.net = new Net(new Net.NetProvider() {
            @Override
            public void connectClient(String ip, int port, Runnable success) {
            }

            @Override
            public void sendClient(Object object, boolean reliable) {
            }

            @Override
            public void disconnectClient() {
            }

            @Override
            public void discoverServers(Cons<Host> callback, Runnable done) {
            }

            @Override
            public void pingHost(String address, int port, Cons<Host> valid, Cons<Exception> failed) {
            }

            @Override
            public void hostServer(int port) {
            }

            @Override
            public Iterable<? extends NetConnection> getConnections() {
                return null;
            }

            @Override
            public void closeServer() {
            }
        });
    }

    @AfterEach
    void tearDown() {
        Core.settings.remove(Gamemode.KEY);
    }

    private Player createMockPlayer() {
        Player player = Player.create();
        player.locale = "en";
        player.name = "TestPlayer";
        player.con = new NetConnection("localhost") {
            @Override
            public void send(Object object, boolean reliable) {
            }

            @Override
            public void close() {
            }
        };
        player.con.player = player;
        return player;
    }

    @Test
    void sandboxGamemodeConditionCheck() {
        Gamemode.set("sandbox");
        assertTrue(new GamemodeCondition(new String[]{"sandbox"}).check());

        Gamemode.set("survival");
        assertFalse(new GamemodeCondition(new String[]{"sandbox"}).check());
    }

    @Test
    void playableTeamsContainStandardTeams() {
        assertEquals(6, SandboxTeamMenu.PLAYABLE_TEAMS.size());
        assertTrue(SandboxTeamMenu.PLAYABLE_TEAMS.contains(Team.sharded));
        assertTrue(SandboxTeamMenu.PLAYABLE_TEAMS.contains(Team.crux));
        assertTrue(SandboxTeamMenu.PLAYABLE_TEAMS.contains(Team.malis));
        assertTrue(SandboxTeamMenu.PLAYABLE_TEAMS.contains(Team.green));
        assertTrue(SandboxTeamMenu.PLAYABLE_TEAMS.contains(Team.blue));
        assertTrue(SandboxTeamMenu.PLAYABLE_TEAMS.contains(Team.neoplastic));
    }

    @Test
    void teamCommandChangesTeamDirectlyByName() {
        SandboxCommands commands = new SandboxCommands(new SandboxTeamMenu());
        Player player = createMockPlayer();
        player.team(Team.sharded);

        Session session = new Session(player, new SessionData());
        commands.team(session, "crux");

        assertEquals(Team.crux, player.team());
    }

    @Test
    void teamCommandChangesTeamDirectlyById() {
        SandboxCommands commands = new SandboxCommands(new SandboxTeamMenu());
        Player player = createMockPlayer();
        player.team(Team.sharded);

        Session session = new Session(player, new SessionData());
        commands.team(session, String.valueOf(Team.blue.id));

        assertEquals(Team.blue, player.team());
    }

    @Test
    void teamCommandWithInvalidTeamLeavesTeamUnchanged() {
        SandboxCommands commands = new SandboxCommands(new SandboxTeamMenu());
        Player player = createMockPlayer();
        player.team(Team.sharded);

        Session session = new Session(player, new SessionData());
        commands.team(session, "nonexistent");

        assertEquals(Team.sharded, player.team());
    }

    @Test
    void menuBuildInitializesUi() {
        SandboxTeamMenu menu = new SandboxTeamMenu();
        Player player = createMockPlayer();

        Session session = new Session(player, new SessionData());
        MenuContext<Void> ctx = new MenuContext<>(session, null, 1);
        menu.build(ctx);

        assertNotNull(menu.getTitle());
        assertNotNull(ctx.getRoot());
    }
}
