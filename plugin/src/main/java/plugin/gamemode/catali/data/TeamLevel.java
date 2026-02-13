package plugin.gamemode.catali.data;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class TeamLevel {
    public int level = 1;
    public float currentExp = 0;
    public float requiredExp = 20;

    public int commonUpgradePoints = 0;
    public int rareUpgradePoints = 0;

    public synchronized boolean addExp(float amount) {
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

        if (level < 10) {
            requiredExp *= 1.35f;
        } else if (level < 25) {
            requiredExp *= 1.20f;
        } else if (level < 50) {
            requiredExp *= 1.15f;
        } else if (level < 100) {
            requiredExp *= 1.05f;
        }

        commonUpgradePoints++;

        if (level % 5 == 0) {
            rareUpgradePoints++;
        }
    }
}
