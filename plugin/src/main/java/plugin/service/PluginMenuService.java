package plugin.service;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import arc.struct.Seq;
import arc.util.Log;
import lombok.RequiredArgsConstructor;
import mindustry.game.EventType.MenuOptionChooseEvent;
import mindustry.gen.Call;
import mindustry.gen.Player;
import plugin.Tasks;
import plugin.annotations.Component;
import plugin.annotations.Destroy;
import plugin.annotations.Listener;
import plugin.annotations.Schedule;
import plugin.event.SessionRemovedEvent;
import plugin.menus.PluginMenu;
import plugin.menus.PluginMenu.HudOption;
import plugin.utils.Utils;

@Component
@RequiredArgsConstructor
public class PluginMenuService {
    private final Seq<PluginMenu<?>> menus = new Seq<>();
    private final ConcurrentHashMap<Player, PluginMenu<?>> activeMenus = new ConcurrentHashMap<>();

    private final SessionHandler sessionHandler;

    @Schedule(fixedDelay = 1, unit = TimeUnit.SECONDS)
    public void cleanStaleMenus() {
        activeMenus.values().removeIf(m -> {
            var delete = Instant.now().isAfter(m.createdAt.plusSeconds(60 * 30));

            if (delete && m.session.player.con != null) {
                try {
                    Call.hideFollowUpMenu(m.session.player.con, m.getMenuId());
                } catch (Exception e) {
                    Log.err("Failed to hide follow up menu", e);
                }
            }

            return delete;
        });
    }

    @Listener
    public void onSessionRemoved(SessionRemovedEvent event) {
        menus.removeAll(m -> m.session.player == event.session.player);
        activeMenus.remove(event.session.player);
    }

    @Listener
    public synchronized void onMenuOptionChoose(MenuOptionChooseEvent event) {
        var targetMenu = activeMenus.remove(event.player);

        if (targetMenu == null) {
            showNext(event.player);
            return;
        }

        if (event.option < 0) {
            showNext(event.player);
            return;
        }

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

        if (selectedOption != null && selectedOption.getCallback() != null) {
            Call.hideFollowUpMenu(event.player.con, targetMenu.getMenuId());

            var session = sessionHandler.get(event.player).orElse(null);

            if (session == null) {
                Log.err("Failed to get session for player @", event.player);
                Thread.dumpStack();
            } else {
                selectedOption.getCallback().accept(session, targetMenu.state);
            }
        }

        showNext(event.player);
    }

    public void showNext(Player player) {
        Utils.appPostWithTimeout(() -> {
            try {
                menus.removeAll(m -> !m.valid());
                var remainingMenus = getPlayerMenus(player);
                var hasActiveMenu = activeMenus.containsKey(player);

                if (!hasActiveMenu && !remainingMenus.isEmpty()) {
                    var menu = remainingMenus.first();

                    menus.remove(menu);
                    activeMenus.put(player, menu);

                    Tasks.io("Show Menu: " + menu.getMenuId(), () -> {
                        try {
                            menu.build();
                        } catch (Exception e) {
                            menu.session.player.sendMessage("[scarlet]Error: [white]" + e.getMessage());
                            Log.err("Failed to build menu @ for player @ with state @", this, menu.session, menu.state);
                            Log.err(e);
                            return;
                        }

                        menu.options.removeAll(op -> op.size == 0);

                        String[][] optionTexts = new String[menu.options.size][];

                        for (int i = 0; i < menu.options.size; i++) {
                            var op = menu.options.get(i);

                            optionTexts[i] = op.map(data -> data.getText()).toArray(String.class);
                        }

                        Call.menu(menu.session.player.con, menu.getMenuId(), menu.title, menu.description, optionTexts);
                    });
                }
            } catch (Exception e) {
                Log.err("Failed to show next menu for player @", player);
                Log.err(e);
                player.sendMessage("[scarlet]Error: [white]" + e.getMessage());
            }
        }, 3000,"Show next menu");
    }

    @Destroy
    public void destroy() {
        menus.clear();
        activeMenus.clear();
    }

    public void add(PluginMenu<?> menu) {
        if (menus.contains(m -> m.getClass().equals(menu.getClass()) && m.session.player == menu.session.player)) {
            return;
        }

        menus.insert(0, menu);
    }

    public Seq<PluginMenu<?>> getPlayerMenus(Player player) {
        return menus.select(m -> m.session.player == player);
    }
}
