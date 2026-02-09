package plugin.gamemode.catali.ai;

import mindustry.entities.Sized;
import mindustry.entities.units.AIController;
import mindustry.gen.Teamc;
import plugin.core.Registry;
import plugin.gamemode.catali.CataliGamemode;

public class BossAi extends AIController {

    public BossAi() {
    }

    @Override
    public void updateMovement() {
        if (target != null) {
            moveTo(target, (target instanceof Sized s ? s.hitSize() / 2f * 1.1f : 0f) + unit.hitSize / 2f + 15f,
                    50f);
            unit.lookAt(target);
        }
    }

    @Override
    public void updateTargeting() {
        if (retarget()) {
            target = findTarget(unit.x, unit.y, unit.range(), true, true);
        }
    }

    @Override
    public Teamc findTarget(float x, float y, float range, boolean air, boolean ground) {
        var strongest = Registry.get(CataliGamemode.class).findStrongestTeam().orElse(null);

        if (strongest != null) {
            return strongest.units().firstOpt();
        }

        return null;
    }
}
