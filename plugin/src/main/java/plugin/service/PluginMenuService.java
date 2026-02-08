package plugin.service;

import java.time.Instant;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import arc.struct.Seq;
import arc.util.Log;
import mindustry.game.EventType.MenuOptionChooseEvent;
import mindustry.gen.Call;
import mindustry.gen.Player;
import plugin.Control;
import plugin.Tasks;
import plugin.annotations.Component;
import plugin.annotations.Destroy;
import plugin.annotations.Init;
import plugin.annotations.Listener;
import plugin.core.Registry;
import plugin.event.SessionRemovedEvent;
import plugin.menus.PluginMenu;
import plugin.menus.PluginMenu.HudOption;

@Component
public class PluginMenuService {
    private final AtomicInteger ID_GEN = new AtomicInteger(1000);
    private final ConcurrentHashMap<Class<?>, Integer> CLASS_IDS = new ConcurrentHashMap<>();
    private final Seq<PluginMenu<?>> menus = new Seq<>();

    @Init
    public void init() {
        Control.SCHEDULER.scheduleWithFixedDelay(() -> {
            menus.removeAll(m -> {
                var delete = Instant.now().isAfter(m.createdAt.plusSeconds(30));

                if (delete && m.session.player.con != null && m.session.player.con.isConnected()) {
                    Call.hideFollowUpMenu(m.session.player.con, m.getMenuId());
                }

                return delete;
            });
        }, 0, 1, TimeUnit.MINUTES);

        Control.SCHEDULER.scheduleWithFixedDelay(() -> {
            HashMap<String, Seq<PluginMenu<?>>> playerMenus = new HashMap<>();

            for (var menu : menus) {
                if (menu.session.player.con == null || !menu.session.player.con.isConnected()) {
                    continue;
                }

                playerMenus.computeIfAbsent(menu.session.player.uuid(), k -> new Seq<>()).add(menu);
            }
        }, 0, 1, TimeUnit.SECONDS);
    }

    @Listener
    public void onSessionRemoved(SessionRemovedEvent event) {
        menus.removeAll(m -> m.session.player == event.session.player);
    }

    @Listener
    public void onMenuOptionChoose(MenuOptionChooseEvent event) {
        var targetMenu = menus.find(m -> m.getMenuId() == event.menuId && m.session.player == event.player);

        if (targetMenu == null) {
            showNext(event.player);
            return;
        }

        menus.remove(targetMenu);

        if (event.option < 0) {
            showNext(event.player);
            return;
        }

        Tasks.io("Menu Option Choose: " + targetMenu.getMenuId(), () -> {
            HudOption<Object> selectedOption = null;

            int i = 0;

            if (event.option >= 0) {
                Seq<HudOption<Object>> flatten = targetMenu.getFlattenedOptions();
                for (var op : flatten) {
                    if (i == event.option) {
                        selectedOption = op;
                        break;
                    }
                    i++;
                }

                if (selectedOption == null) {
                    Log.err("Failed to find selected option for menu @ with id @", targetMenu, event.option);
                }
            }

            synchronized (event.player) {
                if (selectedOption != null && selectedOption.getCallback() != null) {
                    Call.hideFollowUpMenu(event.player.con, targetMenu.getMenuId());

                    var session = Registry.get(SessionHandler.class).get(event.player).orElse(null);

                    if (session == null) {
                        Log.err("Failed to get session for player @", event.player);
                        Thread.dumpStack();
                    } else {
                        selectedOption.getCallback().accept(session, targetMenu.state);
                    }
                }
                showNext(event.player);
            }
        });
    }

    public void showNext(Player player) {
        var remainingMenus = getValidMenus(player);

        if (remainingMenus.size > 0) {
            remainingMenus.first().show();
        }
    }

    @Destroy
    public void destroy() {
        menus.clear();
        CLASS_IDS.clear();
    }

    public int getMenuId(Class<?> cls) {
        return CLASS_IDS.computeIfAbsent(cls, c -> {
            var id = ID_GEN.getAndIncrement();
            return id;
        });
    }

    public void add(PluginMenu<?> menu) {
        menus.insert(0, menu);
    }

    public Seq<PluginMenu<?>> getValidMenus(Player player) {
        return menus.select(m -> m.session.player == player);
    }
}
