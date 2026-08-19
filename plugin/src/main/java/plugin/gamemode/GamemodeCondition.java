package plugin.gamemode;

import plugin.annotations.Condition;

public class GamemodeCondition implements Condition {
    private final String[] modes;

    public GamemodeCondition(String[] modes) {
        this.modes = modes;
    }

    @Override
    public boolean check() {
        return Gamemode.active(modes);
    }
}