package plugin.gamemode.catali.data;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class TeamLevel {
    public int level = 1;
    public float currentExp = 0;
    public float requiredExp = 100;

    public int commonUpgradePoints = 0;
    public int rareUpgradePoints = 0;

    public boolean addExp(float amount) {
        boolean levelUp = false;
        currentExp += amount;
        while (currentExp >= requiredExp) {
            currentExp -= requiredExp;
            levelUp();
            levelUp = true;
        }

        return levelUp;
    }

    private void levelUp() {
        level++;
        requiredExp *= 1.2f;
        commonUpgradePoints++;
        if (level % 5 == 0) {
            rareUpgradePoints++;
        }
    }
}
