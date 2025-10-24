package plugin.type;

import mindustry.gen.Player;

@FunctionalInterface
public interface PlayerPressCallback {
    void accept(Player player, Object state);
}
