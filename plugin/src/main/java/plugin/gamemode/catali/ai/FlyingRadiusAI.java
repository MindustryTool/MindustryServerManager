package plugin.gamemode.catali.ai;

import arc.math.geom.Vec2;
import mindustry.ai.types.FlyingAI;

public class FlyingRadiusAI extends FlyingAI {

    private final Vec2 center = new Vec2();
    private float radius, innerRadius;

    public FlyingRadiusAI(float x, float y, float radius) {
        this.center.set(x, y);
        this.radius = radius;
        this.innerRadius = radius * 0.5f;
    }

    @Override
    public void updateMovement() {
        if (unit == null || unit.dead())
            return;

        if (target == null) {
            moveTo(center, innerRadius);
            return;
        }

        float dst = unit.dst(center);

        if (dst > radius) {
            moveTo(center, radius * 0.6f);

            if (target != null && unit.within(target, unit.range())) {
                unit.lookAt(target);
            }

        } else {
            super.updateMovement();
        }
    }

    @Override
    public void updateTargeting() {
        super.updateTargeting();
    }
}
