package plugin.gamemode.catali.ai;

import mindustry.entities.units.AIController;
import plugin.gamemode.catali.data.CataliTeamData;

public class TeamControlAI extends AIController {
    private final CataliTeamData teamData;

    public TeamControlAI(CataliTeamData team) {
        this.teamData = team;
    }

    @Override
    public void updateMovement() {
        if (unit == null || unit.dead()) {
            return;
        }

        if (!teamData.moveTo.isZero()) {
            moveTo(teamData.moveTo, unit.range() / 2f);
        }

        if (!teamData.shootAt.isZero()) {
            unit.lookAt(teamData.shootAt);
            unit.aim(teamData.shootAt);
            unit.controlWeapons(true);
        }
    }

    @Override
    public void updateTargeting() {
        super.updateTargeting();
    }
}
