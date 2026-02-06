package plugin.gamemode.catali.data;

public class TeamLevel {
    public int level = 1;
    public float currentExp = 0;
    public float requiredExp = 1000;
    
    // Available points
    public int commonUpgradePoints = 0;
    public int rareUpgradePoints = 0;

    public void addExp(float amount) {
        currentExp += amount;
        while (currentExp >= requiredExp) {
            currentExp -= requiredExp;
            levelUp();
        }
    }

    private void levelUp() {
        level++;
        requiredExp *= 1.2f; // Simple scaling
        commonUpgradePoints++;
        if (level % 5 == 0) {
            rareUpgradePoints++;
        }
    }
}
