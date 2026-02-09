package plugin.gamemode.catali.ai;

import arc.math.Mathf;
import arc.math.geom.Vec2;
import mindustry.entities.Sized;
import mindustry.entities.Units;
import mindustry.entities.units.AIController;
import mindustry.gen.Teamc;

public class SwarmAi extends AIController {

    public final Vec2 swarmPosition;
    public final float swarmRange = 8f * 100;

    public SwarmAi(Vec2 swarmPosition) {
        this.swarmPosition = swarmPosition;
    }

    @Override
    public void updateMovement() {
        if (target != null) {
            if (unit.dst(swarmPosition) < swarmRange) {
                moveTo(target, (target instanceof Sized s ? s.hitSize() / 2f * 1.1f : 0f) + unit.hitSize / 2f + 15f,
                        50f);
                unit.lookAt(target);
            } else {
                moveTo(swarmPosition, 40, 50f);
                unit.lookAt(swarmPosition);
            }
        }
    }

    @Override
    public void updateTargeting() {
        if (retarget())
            target = findTarget(unit.x, unit.y, unit.range(), true, true);
    }

    @Override
    public Teamc findTarget(float x, float y, float range, boolean air, boolean ground) {
        var result = Units.closest(unit.team, x, y, Math.max(range, 400f),
                u -> !u.dead() && u.type != unit.type && u.targetable(unit.team) && u.playerControllable(),
                (u, tx, ty) -> -u.maxHealth + Mathf.dst2(u.x, u.y, tx, ty) / 6400f);

        if (result != null)
            return result;

        return null;
    }
}
