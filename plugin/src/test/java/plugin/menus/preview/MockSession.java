package plugin.menus.preview;

import java.util.Locale;
import java.util.Objects;
import common.player.Login;
import mindustry.gen.Player;
import mindustry.net.NetConnection;
import plugin.session.Session;
import plugin.session.SessionData;

public final class MockSession {

    private String locale = "en";
    private String name = "DevPlayer";
    private String uuid = "uuid_dev_test";
    private boolean admin = false;
    private boolean mobile = false;
    private int level = 1;

    private MockSession() {
    }

    public static MockSession builder() {
        return new MockSession();
    }

    public MockSession locale(String locale) {
        this.locale = Objects.requireNonNull(locale, "locale must not be null");
        return this;
    }

    public MockSession name(String name) {
        this.name = Objects.requireNonNull(name, "name must not be null");
        return this;
    }

    public MockSession uuid(String uuid) {
        this.uuid = Objects.requireNonNull(uuid, "uuid must not be null");
        return this;
    }

    public MockSession admin(boolean admin) {
        this.admin = admin;
        return this;
    }

    public MockSession mobile(boolean mobile) {
        this.mobile = mobile;
        return this;
    }

    public MockSession level(int level) {
        this.level = level;
        return this;
    }

    public Session build() {
        Player player = Player.create();
        player.name = name;
        player.locale = locale;

        try {
            var uuidField = player.getClass().getField("uuid");
            uuidField.set(player, uuid);
        } catch (NoSuchFieldException | IllegalAccessException ignored) {
            // Field access depends on entity generation
        }

        player.con = new NetConnection("127.0.0.1") {
            @Override
            public void send(Object object, boolean reliable) {
            }

            @Override
            public void close() {
            }
        };
        player.con.mobile = mobile;

        SessionData data = new SessionData();
        data.name = name;
        data.locale = locale;

        Session session = new Session(player, data);
        session.currentLevel = level;

        Login login = new Login();
        login.setIsAdmin(admin);
        session.login = login;

        return session;
    }
}
