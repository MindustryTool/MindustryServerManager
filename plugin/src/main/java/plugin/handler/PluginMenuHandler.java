package plugin.handler;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import arc.struct.Seq;
import arc.util.Log;
import mindustry.game.EventType.MenuOptionChooseEvent;
import mindustry.gen.Call;
import mindustry.gen.Player;
import plugin.Component;
import plugin.Control;
import plugin.IComponent;
import plugin.PluginEvents;
import plugin.Registry;
import plugin.event.SessionRemovedEvent;
import plugin.menus.PluginMenu;
import plugin.menus.PluginMenu.HudOption;

@Component
public class PluginMenuHandler implements IComponent {
    private final AtomicInteger ID_GEN = new AtomicInteger(1000);
    private final ConcurrentHashMap<Class<?>, Integer> CLASS_IDS = new ConcurrentHashMap<>();
    private final Seq<PluginMenu<?>> menus = new Seq<>();

    @Override
    public void init() {
        PluginEvents.on(MenuOptionChooseEvent.class, event -> {
            var targetMenu = menus.find(m -> m.getMenuId() == event.menuId && m.session.player == event.player);

            if (targetMenu == null) {
                showNext(event.player);
                return;
            }

            if (event.option < 0) {
                showNext(event.player);
                return;
            }

            Control.ioTask("Menu Option Choose: " + targetMenu.getMenuId(), () -> {
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

                    menus.remove(targetMenu);

                    showNext(event.player);
                }
            });
        });

        PluginEvents.on(SessionRemovedEvent.class, event -> {
            menus.removeAll(m -> m.session.player == event.session.player);
        });

        Control.BACKGROUND_SCHEDULER.scheduleWithFixedDelay(() -> {
            menus.removeAll(m -> {
                var delete = Instant.now().isAfter(m.createdAt.plusSeconds(30));

                if (delete && m.session.player.con != null && m.session.player.con.isConnected()) {
                    Call.hideFollowUpMenu(m.session.player.con, m.getMenuId());
                }

                return delete;
            });

            var first = menus.firstOpt();

            if (first != null) {
                first.show();
            }
        }, 0, 1, TimeUnit.MINUTES);
    }

    public void showNext(Player player) {
        var remainingMenus = getMenus(player);

        if (remainingMenus.size > 0) {
            var nextMenu = remainingMenus.first();
            nextMenu.show();
        }
    }

    @Override
    public void destroy() {
        menus.clear();
        CLASS_IDS.clear();
    }

    public int getMenuId(Class<?> cls) {
        return CLASS_IDS.computeIfAbsent(cls, c -> {
            var id = ID_GEN.getAndIncrement();
            Log.info("Register menu @ with id @", c, id);
            return id;
        });
    }

    public void add(PluginMenu<?> menu) {
        menus.add(menu);
    }

    public Seq<PluginMenu<?>> getMenus(Player player) {
        return menus.select(m -> m.session.player == player);
    }
}
