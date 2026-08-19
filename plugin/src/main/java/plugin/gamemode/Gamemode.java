package plugin.gamemode;

import arc.Core;
import arc.util.Log;
import plugin.annotations.Component;
import plugin.annotations.Init;

@Component
public class Gamemode {
    public static final String KEY = "plugin-gamemode";

    @Init
    private void init() {
        String current = current();
        if (current != null && !current.isEmpty()) {
            Log.info("[sky]Current gamemode: " + current);
        }
    }

    public static String current() {
        return Core.settings.getString(KEY, "");
    }

    public static boolean active(String... modes) {
        String current = current();
        for (String mode : modes) {
            if (mode.equalsIgnoreCase(current)) {
                return true;
            }
        }
        return false;
    }

    public static void set(String gamemode) {
        Core.settings.put(KEY, gamemode);
        Core.settings.forceSave();
    }
}