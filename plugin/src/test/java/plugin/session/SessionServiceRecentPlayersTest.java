package plugin.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import dto.RecentPlayerDto;
import mindustry.game.EventType.PlayerLeave;
import mindustry.gen.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class SessionServiceRecentPlayersTest {

    private SessionService sessionService;
    private ConcurrentHashMap<String, Session> data;
    private ConcurrentHashMap<String, Object> recentPlayers;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() throws Exception {
        sessionService = new SessionService(null);

        Field dataField = SessionService.class.getDeclaredField("data");
        dataField.setAccessible(true);
        data = (ConcurrentHashMap<String, Session>) dataField.get(sessionService);

        Field recentField = SessionService.class.getDeclaredField("recentPlayers");
        recentField.setAccessible(true);
        recentPlayers = (ConcurrentHashMap<String, Object>) recentField.get(sessionService);
    }

    private void addRecentPlayer(String uuid, String name, String ip, long joinedAt, long leftAt) throws Exception {
        Class<?> entryClass = Class.forName("plugin.session.SessionService$RecentPlayerEntry");
        Constructor<?> constructor = entryClass.getDeclaredConstructor(RecentPlayerDto.class, long.class);
        constructor.setAccessible(true);

        RecentPlayerDto dto = new RecentPlayerDto()
                .setName(name)
                .setIp(ip)
                .setUuid(uuid)
                .setJoinedAt(joinedAt);

        Object entry = constructor.newInstance(dto, leftAt);
        recentPlayers.put(uuid, entry);
    }

    private long getLeftAt(String uuid) throws Exception {
        Object entry = recentPlayers.get(uuid);
        if (entry == null) return -1;
        Field leftAtField = entry.getClass().getDeclaredField("leftAt");
        leftAtField.setAccessible(true);
        return leftAtField.getLong(entry);
    }

    private void invokeCleanup() throws Exception {
        Method cleanup = SessionService.class.getDeclaredMethod("cleanupRecentPlayers");
        cleanup.setAccessible(true);
        cleanup.invoke(sessionService);
    }

    @Test
    void playerStillInServerIsNotRemovedEvenAfter30Minutes() throws Exception {
        String uuid = "active-player-uuid";
        long sixtyMinutesAgo = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(60);

        addRecentPlayer(uuid, "ActivePlayer", "127.0.0.1", sixtyMinutesAgo, 0);

        Player player = Player.create();
        player.locale = "en";
        player.name = "ActivePlayer";
        player.con = new mindustry.net.NetConnection("127.0.0.1") {
            @Override
            public void send(Object object, boolean reliable) {
            }

            @Override
            public void close() {
            }
        };
        player.con.uuid = uuid;

        Session session = new Session(player, new SessionData());
        data.put(uuid, session);

        invokeCleanup();

        List<RecentPlayerDto> list = sessionService.getRecentPlayers();
        assertEquals(1, list.size());
        assertEquals(uuid, list.get(0).getUuid());
        assertEquals("ActivePlayer", list.get(0).getName());
        assertEquals("127.0.0.1", list.get(0).getIp());
        assertEquals(sixtyMinutesAgo, list.get(0).getJoinedAt());
    }

    @Test
    void playerWhoLeftRecentlyIsNotRemoved() throws Exception {
        String uuid = "left-recent-uuid";
        long fortyMinutesAgo = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(40);
        long tenMinutesAgo = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(10);

        addRecentPlayer(uuid, "LeftRecentPlayer", "127.0.0.1", fortyMinutesAgo, tenMinutesAgo);

        assertFalse(data.containsKey(uuid));

        invokeCleanup();

        List<RecentPlayerDto> list = sessionService.getRecentPlayers();
        assertEquals(1, list.size());
        assertEquals(uuid, list.get(0).getUuid());
    }

    @Test
    void playerWhoLeftMoreThan30MinutesAgoIsRemoved() throws Exception {
        String uuid = "left-expired-uuid";
        long fortyMinutesAgo = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(40);
        long thirtyFiveMinutesAgo = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(35);

        addRecentPlayer(uuid, "LeftExpiredPlayer", "127.0.0.1", fortyMinutesAgo, thirtyFiveMinutesAgo);

        invokeCleanup();

        List<RecentPlayerDto> list = sessionService.getRecentPlayers();
        assertTrue(list.isEmpty());
    }

    @Test
    void fallbackExpirationWhenLeftAtIsZeroAndNotInActiveData() throws Exception {
        String unexpiredUuid = "unexpired-fallback";
        String expiredUuid = "expired-fallback";

        long tenMinutesAgo = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(10);
        long thirtyFiveMinutesAgo = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(35);

        addRecentPlayer(unexpiredUuid, "Unexpired", "127.0.0.1", tenMinutesAgo, 0);
        addRecentPlayer(expiredUuid, "Expired", "127.0.0.1", thirtyFiveMinutesAgo, 0);

        invokeCleanup();

        List<RecentPlayerDto> list = sessionService.getRecentPlayers();
        assertEquals(1, list.size());
        assertEquals(unexpiredUuid, list.get(0).getUuid());
    }

    @Test
    void onPlayerLeaveUpdatesLeftAtTimestamp() throws Exception {
        String uuid = "player-leave-test";
        long joinedAt = System.currentTimeMillis() - 5000;

        addRecentPlayer(uuid, "TestPlayer", "127.0.0.1", joinedAt, 0);

        Player player = Player.create();
        player.name = "TestPlayer";
        player.con = new mindustry.net.NetConnection("127.0.0.1") {
            @Override
            public void send(Object object, boolean reliable) {
            }

            @Override
            public void close() {
            }
        };
        player.con.uuid = uuid;

        sessionService.onPlayerLeave(new PlayerLeave(player));

        long leftAt = getLeftAt(uuid);
        assertTrue(leftAt > 0);
        assertTrue(leftAt >= joinedAt);
    }
}
